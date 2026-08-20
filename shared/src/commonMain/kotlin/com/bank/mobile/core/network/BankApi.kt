package com.bank.mobile.core.network

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.feature.accounts.AccountDto
import com.bank.mobile.feature.beneficiaries.BeneficiaryDto
import com.bank.mobile.feature.beneficiaries.CreateBeneficiaryRequest
import com.bank.mobile.feature.transfer.CreateTransferRequest
import com.bank.mobile.feature.transfer.TransferDto

interface BankApi {
    suspend fun getAccounts(): List<AccountDto>
    suspend fun getBeneficiaries(): List<BeneficiaryDto>
    suspend fun createBeneficiary(request: CreateBeneficiaryRequest): BeneficiaryDto
    suspend fun createTransfer(operationId: OperationId, request: CreateTransferRequest): TransferDto
    suspend fun getTransferByOperationId(operationId: OperationId): TransferDto?
    fun close() = Unit
}
