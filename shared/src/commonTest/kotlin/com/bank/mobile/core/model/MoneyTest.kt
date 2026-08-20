package com.bank.mobile.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MoneyTest {
    @Test fun storesMinorUnitsWithoutFloatingPointConversion() {
        val money = Money(10_099, CurrencyCode("EUR"))
        assertEquals(10_099, money.minorUnits)
    }

    @Test fun rejectsInvalidCurrency() {
        assertFailsWith<IllegalArgumentException> { CurrencyCode("eur") }
    }

    @Test fun parserUsesMinorUnitsWithoutFloatingPoint() {
        val parsed = MinorUnitAmountParser.parse("100,05", CurrencyCode("EUR"))
        assertEquals(10_005, parsed?.minorUnits)
    }

    @Test fun parserRejectsAmbiguousOrOverPreciseInput() {
        val currency = CurrencyCode("EUR")
        assertNull(MinorUnitAmountParser.parse("1,000.00", currency))
        assertNull(MinorUnitAmountParser.parse("10.009", currency))
        assertNull(MinorUnitAmountParser.parse("-1.00", currency))
    }

    @Test fun allocationPreservesEveryMinorUnit() {
        val allocated = Money(10, CurrencyCode("EUR")).allocate(3)
        assertEquals(listOf(4L, 3L, 3L), allocated.map(Money::minorUnits))
        assertEquals(10L, allocated.fold(0L) { total, money -> total + money.minorUnits })
    }

    @Test fun arithmeticRejectsCurrencyMismatchAndNegativeResult() {
        val euros = Money(100, CurrencyCode("EUR"))
        val dollars = Money(50, CurrencyCode("USD"))
        assertFailsWith<IllegalArgumentException> { euros + dollars }
        assertFailsWith<IllegalArgumentException> { dollars - Money(51, CurrencyCode("USD")) }
    }
}
