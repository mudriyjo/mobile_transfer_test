package com.bank.mobile.feature.beneficiaries

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeneficiaryModelsTest {
    @Test
    fun draftNormalizesAllSubmittedFields() {
        val draft = BeneficiaryDraft(
            displayName = "  Taylor   Quinn ",
            accountIdentifier = "gb82 west 1234 5698 7654 32",
            currency = " eur ",
        )

        assertTrue(draft.validate().isValid)
        assertEquals(
            CreateBeneficiaryRequest(
                displayName = "Taylor Quinn",
                accountIdentifier = "GB82WEST12345698765432",
                currency = "EUR",
            ),
            draft.toRequest(),
        )
    }

    @Test
    fun eachInvalidFieldHasItsOwnError() {
        val errors = BeneficiaryDraft("!", "123", "EURO").validate()

        assertFalse(errors.isValid)
        assertTrue(errors.displayName != null)
        assertTrue(errors.accountIdentifier != null)
        assertTrue(errors.currency != null)
    }

    @Test
    fun serverDtoIsReducedToAMaskedSafeDomainValue() {
        val beneficiary = BeneficiaryDto(
            id = "ben-new",
            displayName = "Taylor Quinn",
            maskedAccount = "unexpected-prefix-9876",
            currency = "eur",
        ).toDomain()

        assertEquals("•••• 9876", beneficiary.maskedAccount)
        assertEquals("EUR", beneficiary.currency)
        assertNull(beneficiary.maskedAccount.takeIf { it.contains("unexpected") })
    }
}
