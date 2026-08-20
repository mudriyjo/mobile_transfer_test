package com.bank.mobile.feature.beneficiaries

import kotlinx.serialization.Serializable

data class Beneficiary(
    val id: String,
    val displayName: String,
    val maskedAccount: String,
    val currency: String,
)

@Serializable
data class BeneficiaryDto(
    val id: String,
    val displayName: String,
    val maskedAccount: String,
    val currency: String,
)

@Serializable
data class CreateBeneficiaryRequest(
    val displayName: String,
    val accountIdentifier: String,
    val currency: String,
)

data class BeneficiaryDraft(
    val displayName: String,
    val accountIdentifier: String,
    val currency: String,
) {
    fun normalized(): BeneficiaryDraft = copy(
        displayName = displayName.trim().replace(Regex("\\s+"), " "),
        accountIdentifier = accountIdentifier.filterNot { it.isWhitespace() }.uppercase(),
        currency = currency.trim().uppercase(),
    )

    fun validate(): BeneficiaryValidationErrors {
        val value = normalized()
        return BeneficiaryValidationErrors(
            displayName = when {
                value.displayName.length !in DISPLAY_NAME_LENGTH ->
                    "Use 2 to 60 characters"
                value.displayName.any(Char::isISOControl) ->
                    "Control characters are not allowed"
                value.displayName.none(Char::isLetter) ->
                    "Include at least one letter"
                else -> null
            },
            accountIdentifier = when {
                value.accountIdentifier.length !in ACCOUNT_IDENTIFIER_LENGTH ->
                    "Use 8 to 34 letters or digits"
                value.accountIdentifier.any { !it.isLetterOrDigit() } ->
                    "Use letters and digits only"
                else -> null
            },
            currency = when {
                value.currency.length != 3 || value.currency.any { !it.isLetter() } ->
                    "Use a three-letter currency code"
                else -> null
            },
        )
    }

    internal fun toRequest(): CreateBeneficiaryRequest {
        val value = normalized()
        return CreateBeneficiaryRequest(
            displayName = value.displayName,
            accountIdentifier = value.accountIdentifier,
            currency = value.currency,
        )
    }

    private companion object {
        val DISPLAY_NAME_LENGTH = 2..60
        val ACCOUNT_IDENTIFIER_LENGTH = 8..34
    }
}

data class BeneficiaryValidationErrors(
    val displayName: String? = null,
    val accountIdentifier: String? = null,
    val currency: String? = null,
) {
    val isValid: Boolean
        get() = displayName == null && accountIdentifier == null && currency == null
}

internal fun BeneficiaryDto.toDomain(): Beneficiary {
    val safeSuffix = maskedAccount.takeLast(4).filter(Char::isLetterOrDigit)
    return Beneficiary(
        id = id,
        displayName = displayName.trim(),
        maskedAccount = if (safeSuffix.isEmpty()) "••••" else "•••• $safeSuffix",
        currency = currency.uppercase(),
    )
}
