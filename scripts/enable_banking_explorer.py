"""Enable Banking API exploration tool for goblin.

PC-side tool for exercising the Enable Banking API against the real bank
account, dumping raw responses so the Android client can be designed around
actual data shapes. All credentials, session state, and dumped account data
live under ~/.goblin/ - nothing sensitive is ever written inside the repo.

Requires ~/.goblin/config.json:
    {
      "application_id": "<enable banking application id>",
      "private_key_path": "<absolute path to the application's .pem>"
    }

Usage:
    python enable_banking_explorer.py list-aspsps
    python enable_banking_explorer.py auth
    python enable_banking_explorer.py session <pasted-redirect-url-or-code>
    python enable_banking_explorer.py fetch
"""

import argparse
import json
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import jwt as pyjwt
import requests

API_ORIGIN = "https://api.enablebanking.com"
REDIRECT_URL = "https://localhost:8765/callback"
ASPSP_COUNTRY = "PT"
ASPSP_NAME = "Abanca"
PSU_TYPE = "personal"
REQUESTED_CONSENT_VALIDITY = timedelta(days=180)
TRANSACTION_HISTORY_LOOKBACK = timedelta(days=3 * 365)
SECONDS_BETWEEN_PAGE_REQUESTS = 0.4

GOBLIN_HOME = Path.home() / ".goblin"
CONFIG_FILE = GOBLIN_HOME / "config.json"
PENDING_AUTH_FILE = GOBLIN_HOME / "pending_auth.json"
SESSION_FILE = GOBLIN_HOME / "session.json"
DATA_DIR = GOBLIN_HOME / "data"


def load_config() -> dict:
    if not CONFIG_FILE.exists():
        sys.exit(f"Missing {CONFIG_FILE} - see module docstring for the expected format.")
    return json.loads(CONFIG_FILE.read_text())


def build_authorization_headers(config: dict) -> dict:
    private_key = Path(config["private_key_path"]).read_bytes()
    issued_at = int(datetime.now(timezone.utc).timestamp())
    token = pyjwt.encode(
        {
            "iss": "enablebanking.com",
            "aud": "api.enablebanking.com",
            "iat": issued_at,
            "exp": issued_at + 3600,
        },
        private_key,
        algorithm="RS256",
        headers={"kid": config["application_id"]},
    )
    return {"Authorization": f"Bearer {token}"}


def request_or_die(method: str, url: str, headers: dict, **kwargs) -> requests.Response:
    response = requests.request(method, url, headers=headers, **kwargs)
    if response.status_code == 429:
        print("Rate limited (429), waiting 10s and retrying once...")
        time.sleep(10)
        response = requests.request(method, url, headers=headers, **kwargs)
    if not response.ok:
        sys.exit(
            f"{method} {url} failed with {response.status_code}:\n"
            f"{json.dumps(response.json(), indent=2) if response.text else '<empty body>'}"
        )
    return response


def save_dump(name: str, payload) -> Path:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    path = DATA_DIR / f"{timestamp}_{name}.json"
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    return path


def find_target_aspsp(headers: dict) -> dict:
    response = request_or_die("GET", f"{API_ORIGIN}/aspsps?country={ASPSP_COUNTRY}", headers)
    aspsps = response.json()["aspsps"]
    matches = [a for a in aspsps if ASPSP_NAME.lower() in a["name"].lower()]
    if not matches:
        sys.exit(f"No ASPSP matching '{ASPSP_NAME}' in {ASPSP_COUNTRY}. Run list-aspsps to inspect.")
    if len(matches) > 1:
        print(f"Multiple ASPSPs match '{ASPSP_NAME}', using the first:")
        for match in matches:
            print(f"  - {match['name']}")
    return matches[0]


def command_list_aspsps(config: dict) -> None:
    headers = build_authorization_headers(config)
    response = request_or_die("GET", f"{API_ORIGIN}/aspsps?country={ASPSP_COUNTRY}", headers)
    aspsps = response.json()["aspsps"]
    dump_path = save_dump(f"aspsps_{ASPSP_COUNTRY}", response.json())
    print(f"{len(aspsps)} ASPSPs in {ASPSP_COUNTRY} (full dump: {dump_path})\n")
    for aspsp in aspsps:
        max_validity_days = aspsp.get("maximum_consent_validity", 0) / 86400
        print(f"  {aspsp['name']:40} max consent validity: {max_validity_days:.0f} days")


def command_auth(config: dict) -> None:
    headers = build_authorization_headers(config)
    aspsp = find_target_aspsp(headers)

    max_validity = timedelta(seconds=aspsp.get("maximum_consent_validity", 0))
    consent_validity = min(REQUESTED_CONSENT_VALIDITY, max_validity or REQUESTED_CONSENT_VALIDITY)
    valid_until = datetime.now(timezone.utc) + consent_validity
    state = str(uuid.uuid4())

    body = {
        "access": {"valid_until": valid_until.isoformat()},
        "aspsp": {"name": aspsp["name"], "country": ASPSP_COUNTRY},
        "state": state,
        "redirect_url": REDIRECT_URL,
        "psu_type": PSU_TYPE,
    }
    response = request_or_die("POST", f"{API_ORIGIN}/auth", headers, json=body)

    GOBLIN_HOME.mkdir(parents=True, exist_ok=True)
    PENDING_AUTH_FILE.write_text(json.dumps({"state": state, "requested_valid_until": valid_until.isoformat()}))

    print(f"ASPSP: {aspsp['name']} ({ASPSP_COUNTRY}), consent until {valid_until:%Y-%m-%d} "
          f"({consent_validity.days} days, bank max {max_validity.days} days)")
    print("\nOpen this URL, authenticate with the bank, then run:")
    print("  python enable_banking_explorer.py session <the-full-url-you-land-on>\n")
    print(response.json()["url"])


def command_session(config: dict, redirect_url_or_code: str) -> None:
    if "code=" in redirect_url_or_code:
        from urllib.parse import parse_qs, urlparse
        query = parse_qs(urlparse(redirect_url_or_code).query)
        code = query["code"][0]
        returned_state = query.get("state", [None])[0]
        if PENDING_AUTH_FILE.exists():
            expected_state = json.loads(PENDING_AUTH_FILE.read_text())["state"]
            if returned_state and returned_state != expected_state:
                sys.exit(f"State mismatch: expected {expected_state}, got {returned_state}.")
    else:
        code = redirect_url_or_code

    headers = build_authorization_headers(config)
    response = request_or_die("POST", f"{API_ORIGIN}/sessions", headers, json={"code": code})
    session = response.json()

    SESSION_FILE.write_text(json.dumps(session, indent=2))
    dump_path = save_dump("session", session)

    print(f"Session created and saved to {SESSION_FILE} (dump: {dump_path})")
    print(f"Session id: {session.get('session_id', '<missing>')}")
    print(f"Accounts ({len(session['accounts'])}):")
    for account in session["accounts"]:
        identification = account.get("account_id", {}).get("iban", "<no iban>")
        print(f"  uid {account['uid']}  IBAN {identification}")


def fetch_all_transactions(headers: dict, account_uid: str) -> list:
    date_from = (datetime.now(timezone.utc) - TRANSACTION_HISTORY_LOOKBACK).strftime("%Y-%m-%d")
    transactions = []
    continuation_key = None
    page_number = 0
    while True:
        page_number += 1
        # Abanca's connector requires the original query params to be repeated
        # on every page together with the continuation key.
        url = f"{API_ORIGIN}/accounts/{account_uid}/transactions?date_from={date_from}"
        if continuation_key:
            url += f"&continuation_key={continuation_key}"
        response = request_or_die("GET", url, headers)
        payload = response.json()
        transactions.extend(payload["transactions"])
        continuation_key = payload.get("continuation_key")
        print(f"    page {page_number}: {len(payload['transactions'])} transactions"
              f"{' (more pages follow)' if continuation_key else ''}")
        if not continuation_key:
            return transactions
        time.sleep(SECONDS_BETWEEN_PAGE_REQUESTS)


def command_fetch(config: dict) -> None:
    if not SESSION_FILE.exists():
        sys.exit(f"No session at {SESSION_FILE} - run auth + session first.")
    session = json.loads(SESSION_FILE.read_text())
    headers = build_authorization_headers(config)

    for account in session["accounts"]:
        uid = account["uid"]
        iban = account.get("account_id", {}).get("iban", uid)
        label = iban[-4:] if len(iban) >= 4 else uid[:8]
        print(f"\nAccount IBAN {iban}")

        balances_payload = request_or_die("GET", f"{API_ORIGIN}/accounts/{uid}/balances", headers).json()
        balances_path = save_dump(f"balances_{label}", balances_payload)
        for balance in balances_payload["balances"]:
            amount = balance["balance_amount"]
            print(f"  balance [{balance.get('balance_type', '?')}] {amount['amount']} {amount['currency']}")
        print(f"  saved: {balances_path}")

        print("  fetching transactions...")
        transactions = fetch_all_transactions(headers, uid)
        transactions_path = save_dump(f"transactions_{label}", transactions)
        if transactions:
            dates = sorted(t.get("booking_date") or t.get("value_date") or "" for t in transactions)
            print(f"  {len(transactions)} transactions from {dates[0]} to {dates[-1]}")
        else:
            print("  0 transactions returned")
        print(f"  saved: {transactions_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("list-aspsps", help="List Portuguese ASPSPs and their consent validity limits")
    subparsers.add_parser("auth", help="Start bank authorization, prints the URL to open")
    session_parser = subparsers.add_parser("session", help="Complete authorization with the redirect URL or code")
    session_parser.add_argument("redirect_url_or_code")
    subparsers.add_parser("fetch", help="Fetch and dump balances + full transaction history for all accounts")

    args = parser.parse_args()
    config = load_config()
    if args.command == "list-aspsps":
        command_list_aspsps(config)
    elif args.command == "auth":
        command_auth(config)
    elif args.command == "session":
        command_session(config, args.redirect_url_or_code)
    elif args.command == "fetch":
        command_fetch(config)


if __name__ == "__main__":
    main()
