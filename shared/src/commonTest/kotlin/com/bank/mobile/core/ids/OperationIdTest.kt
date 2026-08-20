package com.bank.mobile.core.ids

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationIdTest {
    @Test fun rejectsBlankValues() {
        assertFailsWith<IllegalArgumentException> { OperationId("") }
    }

    @Test fun randomProviderProducesDistinctValues() {
        val provider = RandomOperationIdProvider()
        assertTrue(provider.next() != provider.next())
    }
}
