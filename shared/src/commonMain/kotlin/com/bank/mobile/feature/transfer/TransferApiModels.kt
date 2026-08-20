package com.bank.mobile.feature.transfer

import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import kotlinx.serialization.Serializable

@Serializable
data class CreateTransferRequest(
    val fromAccountId: String,
    val toAccountId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val reference: String? = null,
) {
    init {
        require(fromAccountId.isNotBlank())
        require(toAccountId.isNotBlank())
        require(amountMinorUnits > 0L)
        require(currency.length == 3 && currency.all(Char::isUpperCase))
    }

    val payload: TransferRequestPayload
        get() = TransferRequestPayload(
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amountMinorUnits = amountMinorUnits,
            currency = currency,
            reference = reference,
        )

    fun toDraft(): TransferDraft = TransferDraft(
        fromAccountId = fromAccountId,
        beneficiaryId = toAccountId,
        amount = Money(amountMinorUnits, CurrencyCode(currency)),
        reference = reference,
    )
}

data class TransferRequestPayload(
    val fromAccountId: String,
    val toAccountId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val reference: String?,
) {
    fun canonicalFields(): List<String> = listOf(
        fromAccountId,
        toAccountId,
        amountMinorUnits.toString(),
        currency,
        reference.orEmpty(),
    )

    fun stableSignature(): String = canonicalFields()
        .joinToString(separator = "\u001f")
        .fold(0xcbf29ce484222325UL) { hash, character ->
            (hash xor character.code.toULong()) * 0x100000001b3UL
        }
        .toString(radix = 16)

    fun matches(dto: TransferDto): Boolean =
        fromAccountId == dto.fromAccountId &&
            toAccountId == dto.toAccountId &&
            amountMinorUnits == dto.amountMinorUnits &&
            currency == dto.currency &&
            reference.orEmpty() == dto.reference.orEmpty()
}

@Serializable
enum class TransferDtoStatus { PROCESSING, COMPLETED, REJECTED }

@Serializable
data class TransferDto(
    val transferId: String,
    val operationId: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val reference: String? = null,
    val status: TransferDtoStatus,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(transferId.isNotBlank())
        require(operationId.isNotBlank())
        require(fromAccountId.isNotBlank())
        require(toAccountId.isNotBlank())
        require(amountMinorUnits > 0L)
        require(currency.length == 3 && currency.all(Char::isUpperCase))
        require(createdAt.isNotBlank())
        require(updatedAt.isNotBlank())
    }

    val payload: TransferRequestPayload
        get() = TransferRequestPayload(
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amountMinorUnits = amountMinorUnits,
            currency = currency,
            reference = reference,
        )

    val isTerminal: Boolean
        get() = status == TransferDtoStatus.COMPLETED || status == TransferDtoStatus.REJECTED

    fun validateAgainst(request: CreateTransferRequest): TransferResponseValidation {
        val mismatches = buildList {
            if (fromAccountId != request.fromAccountId) add(TransferPayloadField.FROM_ACCOUNT)
            if (toAccountId != request.toAccountId) add(TransferPayloadField.TO_ACCOUNT)
            if (amountMinorUnits != request.amountMinorUnits) add(TransferPayloadField.AMOUNT)
            if (currency != request.currency) add(TransferPayloadField.CURRENCY)
            if (reference.orEmpty() != request.reference.orEmpty()) add(TransferPayloadField.REFERENCE)
        }
        return if (mismatches.isEmpty()) {
            TransferResponseValidation.Accepted
        } else {
            TransferResponseValidation.PayloadMismatch(mismatches)
        }
    }

    fun equivalentPayload(other: TransferDto): Boolean = payload == other.payload
}

enum class TransferPayloadField {
    FROM_ACCOUNT,
    TO_ACCOUNT,
    AMOUNT,
    CURRENCY,
    REFERENCE,
}

sealed interface TransferResponseValidation {
    data object Accepted : TransferResponseValidation

    data class PayloadMismatch(
        val fields: List<TransferPayloadField>,
    ) : TransferResponseValidation {
        init {
            require(fields.isNotEmpty())
        }
    }
}

data class TransferStatusEnvelope(
    val operationId: String,
    val transferId: String?,
    val status: TransferDtoStatus?,
    val response: TransferDto?,
) {
    init {
        require(operationId.isNotBlank())
        if (response != null) {
            require(response.transferId == transferId)
            require(response.status == status)
        }
    }

    val found: Boolean
        get() = response != null

    val isFinal: Boolean
        get() = status == TransferDtoStatus.COMPLETED || status == TransferDtoStatus.REJECTED

    companion object {
        fun missing(operationId: String) = TransferStatusEnvelope(
            operationId = operationId,
            transferId = null,
            status = null,
            response = null,
        )

        fun found(operationId: String, response: TransferDto) = TransferStatusEnvelope(
            operationId = operationId,
            transferId = response.transferId,
            status = response.status,
            response = response,
        )
    }
}

internal fun TransferDtoStatus.toDomain(): TransferStatus = when (this) {
    TransferDtoStatus.PROCESSING -> TransferStatus.PROCESSING
    TransferDtoStatus.COMPLETED -> TransferStatus.COMPLETED
    TransferDtoStatus.REJECTED -> TransferStatus.REJECTED
}

internal fun TransferStatus.toDtoStatusOrNull(): TransferDtoStatus? = when (this) {
    TransferStatus.PROCESSING -> TransferDtoStatus.PROCESSING
    TransferStatus.COMPLETED -> TransferDtoStatus.COMPLETED
    TransferStatus.REJECTED -> TransferDtoStatus.REJECTED
    TransferStatus.SUBMITTING,
    TransferStatus.OUTCOME_UNKNOWN,
    TransferStatus.FAILED,
    -> null
}
