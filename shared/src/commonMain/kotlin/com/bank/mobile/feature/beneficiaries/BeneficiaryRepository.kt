package com.bank.mobile.feature.beneficiaries

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bank.mobile.core.network.BankApi
import com.bank.mobile.db.MobileBankDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface BeneficiaryRepository {
    fun observe(): Flow<List<Beneficiary>>
    suspend fun refresh()
    suspend fun create(draft: BeneficiaryDraft): Beneficiary
}

class BeneficiaryRepositoryImpl(
    private val database: MobileBankDatabase,
    private val api: BankApi,
) : BeneficiaryRepository {
    override fun observe(): Flow<List<Beneficiary>> =
        database.mobileBankQueries.selectBeneficiaries().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { Beneficiary(it.beneficiary_id, it.display_name, it.masked_account, it.currency) }
        }

    override suspend fun refresh() {
        val beneficiaries = api.getBeneficiaries().map(BeneficiaryDto::toDomain)
        database.transaction {
            database.mobileBankQueries.deleteBeneficiaries()
            beneficiaries.forEach(::save)
        }
    }

    override suspend fun create(draft: BeneficiaryDraft): Beneficiary {
        val errors = draft.validate()
        require(errors.isValid) { "Beneficiary draft is invalid" }
        val beneficiary = api.createBeneficiary(draft.toRequest()).toDomain()
        database.transaction { save(beneficiary) }
        return beneficiary
    }

    private fun save(beneficiary: Beneficiary) {
        database.mobileBankQueries.replaceBeneficiary(
            beneficiary_id = beneficiary.id,
            display_name = beneficiary.displayName,
            masked_account = beneficiary.maskedAccount,
            currency = beneficiary.currency,
        )
    }
}
