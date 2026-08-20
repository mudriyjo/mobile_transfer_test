package com.bank.backend

class BankDirectory {
    private val accounts = listOf(
        AccountDto(
            id = "acc-checking-eur",
            displayName = "Everyday account",
            balanceMinorUnits = 284_550,
            currency = "EUR",
            updatedAt = "2026-08-19T08:00:00Z",
        ),
        AccountDto(
            id = "acc-savings-eur",
            displayName = "Savings account",
            balanceMinorUnits = 1_025_000,
            currency = "EUR",
            updatedAt = "2026-08-19T08:00:00Z",
        ),
    )

    private val beneficiaries = mutableListOf(
        StoredBeneficiary(
            id = "ben-alex",
            displayName = "Alex Morgan",
            accountIdentifier = "ACCALEXEUR9031",
            currency = "EUR",
            destinationAliases = setOf("acc-alex-eur"),
        ),
        StoredBeneficiary(
            id = "ben-sam",
            displayName = "Sam Rivera",
            accountIdentifier = "ACCSAMEUR2260",
            currency = "EUR",
            destinationAliases = setOf("acc-sam-eur"),
        ),
    )
    private var nextBeneficiarySequence = 1

    fun accounts(): List<AccountDto> = accounts

    @Synchronized
    fun beneficiaries(): List<BeneficiaryDto> = beneficiaries.map(StoredBeneficiary::toDto)

    @Synchronized
    fun createBeneficiary(request: CreateBeneficiaryRequest): CreateBeneficiaryResult {
        val normalized = request.normalized()
        if (beneficiaries.any { it.accountIdentifier == normalized.accountIdentifier }) {
            return CreateBeneficiaryResult.Conflict
        }

        val stored = StoredBeneficiary(
            id = "ben-saved-${nextBeneficiarySequence++.toString().padStart(4, '0')}",
            displayName = normalized.displayName,
            accountIdentifier = normalized.accountIdentifier,
            currency = normalized.currency,
        )
        beneficiaries += stored
        return CreateBeneficiaryResult.Created(stored.toDto())
    }

    fun account(id: String): AccountDto? = accounts.firstOrNull { it.id == id }

    @Synchronized
    fun destinationAccount(id: String): DestinationAccount? =
        beneficiaries.firstOrNull {
            it.id == id || it.accountIdentifier == id || id in it.destinationAliases
        }?.let {
            DestinationAccount(id = id, currency = it.currency)
        }
}

private data class StoredBeneficiary(
    val id: String,
    val displayName: String,
    val accountIdentifier: String,
    val currency: String,
    val destinationAliases: Set<String> = emptySet(),
) {
    fun toDto(): BeneficiaryDto = BeneficiaryDto(
        id = id,
        displayName = displayName,
        maskedAccount = "•••• ${accountIdentifier.takeLast(4)}",
        currency = currency,
    )
}

sealed interface CreateBeneficiaryResult {
    data class Created(val beneficiary: BeneficiaryDto) : CreateBeneficiaryResult
    data object Conflict : CreateBeneficiaryResult
}

data class DestinationAccount(
    val id: String,
    val currency: String,
)
