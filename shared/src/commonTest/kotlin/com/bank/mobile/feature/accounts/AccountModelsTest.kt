package com.bank.mobile.feature.accounts

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountModelsTest {
    @Test fun dtoMappingMasksOnlyTheDisplayIdentifier() {
        val account = AccountDto("account-1234", "Everyday", 999, "EUR", "now").toDomain(42)
        assertEquals("•••• 1234", account.maskedNumber)
        assertEquals(999, account.balance.minorUnits)
    }

    @Test fun dtoMappingUsesAStableNumericFallbackForSyntheticIds() {
        val account = AccountDto("acc-checking-eur", "Everyday", 999, "EUR", "now").toDomain(42)

        assertEquals("•••• 0955", account.maskedNumber)
    }
}
