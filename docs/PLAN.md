# goblin - Plan

The blueprint for the goblin Android app. Written 2026-07-09, after the API
exploration phase proved the full pipeline (see `scripts/enable_banking_explorer.py`
and the facts in section 5). This document is the source of truth for what gets
built and in what order.

## 1. Vision and principles

**Problem:** checking finances through the bank app costs enough friction
(open app, PIN, navigate) that it happens rarely. Awareness suffers.

**Product:** the numbers are simply *there* on the home screen after unlock.
Zero interaction to see them; one tap to explore them.

Principles, in priority order:

1. **Glanceability beats completeness.** The widget is the product. The app
   behind it exists to answer the questions the widget raises.
2. **Trustworthy numbers.** A wrong "spent this week" is worse than none:
   internal transfers between own accounts must never count as spending,
   comparisons must compare like with like, and stale data must be visibly
   stale.
3. **Everything local.** No server, no cloud, no analytics. Data exists on the
   phone and at the bank, nowhere else.
4. **Motivation through comparison.** Deltas ("20 EUR less than last week")
   drive behavior more than absolute numbers.

## 2. Product spec

### 2.1 The widget (Jetpack Glance)

Responsive sizes via `SizeMode.Responsive`:

- **Small (2x1):** total balance across accounts + spent-this-week delta vs
  last week (arrow + amount, green/red).
- **Medium (4x1):** adds spent-this-week and received-this-week figures and a
  freshness line ("synced 14:32").
- **Large (4x2):** adds the 3 most recent transactions (description, amount)
  and per-account balances.

Widget states beyond data: `needs re-auth` (consent expiring/expired, tap
deep-links into re-auth), `sync failing` (>24h stale), `setup required`.
Freshness text always present; the widget must never present old numbers as
current.

Tap anywhere: opens the app dashboard. v1 widget is text-only (no charts);
a balance sparkline bitmap is a later polish item (section 10).

### 2.2 The app screens

- **Dashboard:** total + per-account balances, balance-over-time curve
  (30d / 90d / 1y / 3y, reconstructed from `balance_after_transaction`),
  this-week and this-month summary cards with comparison deltas.
- **Transactions:** unified list across accounts, search, filter by
  account/direction/category, internal transfers visually marked.
- **Insights:** the money-management layer (section 2.4).
- **Settings:** sync status + log, manual refresh, consent status with renew
  action, account nicknames, category rules editor, data export.

### 2.3 Notifications

- **Consent expiry:** at T-14, T-7, T-1 days. Tap -> re-auth flow. This is
  load-bearing: a silent consent lapse kills the whole product.
- **Sync failure:** only after >24h of consecutive failures (transient bank
  errors are common; no cry-wolf).
- **Weekly recap (Sunday evening):** "Week: -312.40 EUR spent, +0 received.
  23.10 EUR less than last week." Zero-effort motivation. Configurable off.
- **Large transaction:** when a sync lands a debit above a configurable
  threshold. Delayed by sync cadence (up to ~6h); still catches surprises.

### 2.4 Money-management features (the "boss mode" additions)

Ranked; all computable offline from local data:

1. **Correct spend math (foundation, not a feature):** "spent" = DBIT minus
   internal transfers; "received" = CRDT minus internal transfers. Internal
   transfer detection: opposite-direction pairs across own accounts with equal
   amount within a small date window, plus remittance-text heuristics.
2. **Aligned comparisons:** week vs previous week; month-to-date vs previous
   month *to the same day* (July 9th compares against June 1-9, not all of
   June). Honest deltas or the motivation effect backfires.
3. **Recurring payment detection:** cluster debits by normalized remittance
   text; flag ~monthly/weekly cadence with similar amounts. Yields the
   **Fixed costs** view: subscriptions, rent, utilities, their monthly total,
   and price-creep alerts ("Spotify went from 11.99 to 13.99").
4. **Income detection + pay cycle:** recurring CRDT = salary. Unlocks
   salary-to-salary period framing, which matches how money is actually
   experienced better than calendar months.
5. **Safe-to-spend:** balance minus recurring debits expected before the next
   detected income. The single most actionable number in the app. Lives as a
   dashboard/insights card (the widget headline stays the real balance).
6. **Categorization:** rule-based text matching on `remittance_information`
   (ABANCA provides no merchant category codes). Seeded with Portuguese
   defaults (Continente, Pingo Doce, Galp, MB WAY patterns, ...), fully
   user-editable rules, every match inspectable. No ML, no cloud.
7. **Month-end projection:** current run-rate -> projected month spend vs
   typical month (median of last 6).
8. **Anomaly cards (later):** "groceries this week are 2x your average".

## 3. Data and domain model

Room database, entities (keys chosen from verified API behavior):

- `Account(iban PK, nickname, displayOrder)` - keyed by IBAN, not the EB
  account uid (the uid changes on every authorization; see section 5).
- `BankTransaction(accountIban, entryReference, amountCents, currency,
  creditDebitIndicator, status, bookingDate, valueDate,
  balanceAfterTransactionCents, remittanceLines, categoryId?,
  internalTransferPairId?)` - PK composite `(accountIban, entryReference)`;
  `transaction_id` from the API is always null for ABANCA, `entry_reference`
  is the real (stable) identifier. Upserts make sync idempotent across re-auths.
- `BalanceSnapshot(accountIban, capturedAt, balanceCents, balanceType)` - one
  per account per sync; the widget reads the latest, curves interpolate
  between transaction-level running balances and snapshots.
- `CategoryRule(id, pattern, matchType, categoryId, priority)` and
  `Category(id, name, colorToken)`
- `RecurringSeries(id, normalizedLabel, cadence, typicalAmountCents,
  lastSeenDate, kind: expense|income)` - materialized by the detector,
  re-derived after each sync.
- `SyncLog(id, startedAt, finishedAt, outcome, detail)`
- Preferences (DataStore): consent `valid_until`, widget options,
  notification toggles, thresholds.
- Encrypted storage (Keystore-backed, not Room): application private key
  (imported .pem), application id, EB session id.

Money is stored as long cents + currency code. Display uses 2 decimals
(banking data is 2dp; the F3+ default is deliberately reduced here).

## 4. Architecture

- **Stack:** Kotlin, Jetpack Compose (app UI), Glance (widget), Room,
  WorkManager, DataStore, Hilt (DI, incl. HiltWorker), Retrofit + OkHttp +
  kotlinx.serialization, Material 3. minSdk 26, target latest.
- **Structure:** single `:app` module with strict package layering -
  `data` (Room, EB API client, repositories), `domain` (pure logic: all of
  section 2.4, comparison math, transfer detection - plain Kotlin, no Android
  imports), `sync` (workers, backfill planner), `widget` (Glance),
  `ui` (Compose screens), `auth` (key import, consent flow). Multi-module
  buys nothing at this size; package discipline + domain purity gives the
  same testability.
- **JWT signing:** hand-rolled RS256 (~30 lines: PEM -> PKCS8 -> KeyFactory ->
  `SHA256withRSA`, base64url header.payload.signature). Zero dependencies;
  unit-tested against a reference token generated by the Python explorer.
- **Auth flow on device:** Custom Tabs -> bank SCA -> redirect to App Link
  `https://luisperestrelo.github.io/goblin/auth-callback` -> intent filter
  (autoVerify) captures `code` -> POST /sessions. Requires the
  `luisperestrelo.github.io` Pages site serving `/.well-known/assetlinks.json`
  (with `.nojekyll`) listing the app signing cert SHA-256, and the URL added
  to the EB app's redirect list. The callback page itself is a static HTML
  fallback showing the code with a copy button, in case App Link verification
  ever misbehaves.
- **Key onboarding (first run):** setup screen -> paste application id ->
  SAF file picker imports the .pem -> stored via Keystore-backed encryption ->
  authorize -> deep backfill. The key is never bundled in the APK and never
  leaves app-private storage.
- **Sync engine (WorkManager):**
  - Periodic worker every 6h (respects the PSD2 4-unattended-calls/day cap).
  - Incremental sync: `date_from` = newest local `bookingDate` minus 5 days
    overlap; upsert by PK. Every page repeats `date_from` alongside
    `continuation_key` (ABANCA 422s otherwise - verified).
  - Post-auth backfill: immediately after any (re-)authorization, a one-time
    expedited worker pulls 3 years per account (the deep-history window is
    ~1h after SCA - verified that ABANCA honors 3y).
  - Manual refresh from app/settings; if the bank rate-limits (429), surface
    "bank limit reached, next auto-sync at HH:MM" instead of an error.
  - After every successful sync: recompute derived data (recurring series,
    aggregates), then `GlanceAppWidget.updateAll`.
- **Consent lifecycle:** `valid_until` tracked; notification schedule; re-auth
  reuses the same auth flow; success triggers backfill + resets warnings.
- **Security posture:** private key and session id in Keystore-encrypted
  storage; Room db relies on Android file-based encryption + app sandbox
  (no SQLCipher - it defends against nothing the sandbox doesn't on a
  non-rooted personal device, and costs real friction); read-only AIS scope
  by construction; kill switch = delete the app in the EB control panel.

## 5. Enable Banking integration facts (verified 2026-07-09)

Everything the Kotlin client must honor, learned via the explorer:

- ASPSP name is exactly `"Abanca"`, country `"PT"`, psu_type `personal`.
- Max consent validity 180 days. Current consent runs to 2027-01-05.
- Auth: POST /auth (access.valid_until, aspsp, state, redirect_url, psu_type)
  -> user completes SCA at returned URL -> `code` in redirect query ->
  POST /sessions {code} -> session with account uids.
- Redirect URLs must be https (no custom schemes, no http).
- Two accounts (..7715 main, ..1886 secondary). Restricted app returns only
  whitelisted accounts.
- Account `uid` is NOT stable: Enable Banking mints a new account uid on every
  authorization for the same IBAN (verified in phase-2 device testing,
  2026-07-10 - a re-auth duplicated the whole history under new uids). Key all
  local storage by IBAN; treat uid as a throwaway per-session API handle only.
  `entry_reference` by contrast is stable (deterministic timestamp+amount).
- GET /accounts/{uid}/balances -> balance_type `ITAV` (interim available).
- GET /accounts/{uid}/transactions?date_from=YYYY-MM-DD, 50/page,
  `continuation_key` for next page, **date_from must be repeated on every
  page**.
- Transaction fields always present: entry_reference, transaction_amount
  {currency, amount-as-string}, credit_debit_indicator DBIT|CRDT, status BOOK,
  booking_date, value_date, balance_after_transaction, remittance_information
  [lines]. Always null: transaction_id, merchant_category_code,
  creditor/debtor details, bank_transaction_code.
- History depth: at least 3 years (full requested window returned; deeper not
  yet probed). Deep history reliably available ~1h post-SCA; assume 90 days
  otherwise.
- Redsys constraint: one active authentication session per user per TPP - a
  new auth invalidates the previous session. Fine for a single app; never
  connect these accounts to another Enable-Banking-backed service.
- Rate limits: unattended AIS access capped at 4/day (PSD2); handle 429s
  gracefully.

## 6. Build phases

Each phase ends committed, pushed, and verified on the real device.

1. **Scaffold + data core.** Android project (Kotlin, Compose, Hilt, Room),
   EB API client with JWT signing, DTOs, Room schema, repository layer, a
   debug screen that performs a manual sync using a session id provisioned
   from the PC explorer. Unit tests: JWT vector, DTO parsing, cents math.
   *Milestone: app on phone lists real balances + recent transactions.*
2. **Device auth.** GitHub Pages assetlinks + callback page, signing keystore,
   setup/import flow, Custom Tabs auth, session storage, post-auth 3y
   backfill engine. *Milestone: fresh install -> authorize -> full history
   local, no PC involved.*
3. **Sync engine + widget MVP.** Periodic workers, freshness tracking, Glance
   widget small+medium with balance, week spend + delta, sync state.
   *Milestone: the original goal - unlock phone, numbers are there.*
4. **Correct math + core insights.** Internal-transfer detection, aligned
   week/month comparisons, dashboard with balance curve, transactions screen,
   consent-expiry notifications. *Milestone: numbers are trustworthy.*
5. **Money management.** Categorizer + rules UI + PT seed rules, recurring
   detection, fixed-costs view, income/pay-cycle, safe-to-spend, weekly recap
   + large-transaction notifications, large widget size.
6. **Polish.** Balance sparkline in widget, dark/light widget theming, data
   export/import (JSON), error-state refinement, optional widget privacy mode.

## 7. Prerequisites checklist (session 1 start)

- [ ] Android Studio + SDK installed on the PC (verify; install if missing)
- [ ] Physical device, developer mode + USB debugging (walk through it)
- [ ] Create app signing keystore (needed for stable App Link cert hash;
      back it up outside the repo)
- [ ] Create `luisperestrelo.github.io` repo (Pages user site) - hosts
      assetlinks.json + callback page (added in phase 2)
- [ ] Add `https://luisperestrelo.github.io/goblin/auth-callback` to the EB
      application's redirect URLs (control panel)
- [ ] applicationId: `com.luisperestrelo.goblin`

## 8. Testing strategy

Domain logic is deliberately pure Kotlin and gets real tests: comparison
windows (incl. month-alignment edge cases: Jan 31, DST, year boundary),
internal-transfer pairing, categorizer rule precedence, recurring-series
detection (cadence tolerance, amount drift), safe-to-spend, backfill planner
(window math, overlap dedup), JWT builder (reference vector), transaction
upsert idempotency (Room instrumented test). UI, Glance rendering, and
WorkManager glue are verified on-device per phase, not unit-tested.

## 9. Out of scope

Payments of any kind (impossible under AIS scope - by design), budgets with
manual data entry, multi-user, cloud sync/backup, iOS, Wear OS, ML
categorization. Multi-bank support is out of scope but the schema keys by
IBAN everywhere (globally unique across banks), so a second EB-supported bank
later is additive, not a migration.

## 10. Later / maybe

Widget sparkline bitmap, MB WAY counterparty extraction from remittance text,
CSV export, savings-goal card, ABANCA push-notification listener for
near-real-time transaction pings between syncs (NotificationListenerService,
local-only), probing whether ABANCA serves >3y history at next re-auth.
