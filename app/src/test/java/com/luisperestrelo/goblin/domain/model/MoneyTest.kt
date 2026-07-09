package com.luisperestrelo.goblin.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {

    @Test
    fun `parses api amount strings exactly`() {
        assertThat(Money.parse("1145.07", "EUR").cents).isEqualTo(114507)
        assertThat(Money.parse("0.00", "EUR").cents).isEqualTo(0)
        assertThat(Money.parse("12.3", "EUR").cents).isEqualTo(1230)
        assertThat(Money.parse("7", "EUR").cents).isEqualTo(700)
        assertThat(Money.parse("-3.50", "EUR").cents).isEqualTo(-350)
    }

    @Test
    fun `rejects amounts with more than two decimals`() {
        assertThrows(ArithmeticException::class.java) { Money.parse("12.345", "EUR") }
    }

    @Test
    fun `formats including small negative amounts`() {
        assertThat(Money(114507, "EUR").formatted()).isEqualTo("1145.07 EUR")
        assertThat(Money(-350, "EUR").formatted()).isEqualTo("-3.50 EUR")
        assertThat(Money(-50, "EUR").formatted()).isEqualTo("-0.50 EUR")
        assertThat(Money(5, "EUR").formatted()).isEqualTo("0.05 EUR")
        assertThat(Money(0, "EUR").formatted()).isEqualTo("0.00 EUR")
    }

    @Test
    fun `arithmetic requires matching currencies`() {
        assertThat((Money(100, "EUR") + Money(50, "EUR")).cents).isEqualTo(150)
        assertThat((Money(100, "EUR") - Money(150, "EUR")).cents).isEqualTo(-50)
        assertThrows(IllegalArgumentException::class.java) { Money(1, "EUR") + Money(1, "USD") }
    }
}
