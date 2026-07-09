package com.luisperestrelo.goblin.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Monetary amount stored as integer cents to keep arithmetic exact.
 * The Enable Banking API transports amounts as decimal strings (e.g. "1145.07").
 */
data class Money(val cents: Long, val currency: String) {

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add $currency to ${other.currency}" }
        return Money(cents + other.cents, currency)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Cannot subtract ${other.currency} from $currency" }
        return Money(cents - other.cents, currency)
    }

    fun formatted(): String {
        val sign = if (cents < 0) "-" else ""
        val absolute = abs(cents)
        return "%s%d.%02d %s".format(sign, absolute / 100, absolute % 100, currency)
    }

    companion object {
        fun parse(amount: String, currency: String): Money {
            val cents = BigDecimal(amount)
                .setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact()
            return Money(cents, currency)
        }
    }
}
