package com.bank.backend

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

class TransferLedger(
    private val clock: Clock = Clock.systemUTC(),
    private val transferIdProvider: () -> String = { "tr-${UUID.randomUUID()}" },
) {
    private val lock = Any()
    private val byOperationId = linkedMapOf<String, StoredTransfer>()
    private val operationIdByTransferId = mutableMapOf<String, String>()
    private val journal = mutableListOf<JournalEntryDto>()
    private var nextSequence = 1L

    fun submit(operationId: String, request: CreateTransferRequest): SubmitResult = synchronized(lock) {
        val fingerprint = request.fingerprint()
        val existing = byOperationId[operationId]

        if (existing != null) {
            if (existing.payloadFingerprint == fingerprint) {
                appendEvent(
                    type = JournalEventType.IDEMPOTENT_REPLAY,
                    operation = existing.transfer,
                )
                return@synchronized SubmitResult.Replay(existing.transfer)
            }

            appendEvent(
                type = JournalEventType.IDEMPOTENCY_CONFLICT,
                operation = existing.transfer,
            )
            return@synchronized SubmitResult.Conflict(existing.transfer)
        }

        val now = clock.instant().toString()
        val transfer = TransferDto(
            transferId = transferIdProvider(),
            operationId = operationId,
            fromAccountId = request.fromAccountId,
            toAccountId = request.toAccountId,
            amountMinorUnits = request.amountMinorUnits,
            currency = request.currency,
            reference = request.reference,
            status = TransferStatus.PROCESSING,
            createdAt = now,
            updatedAt = now,
        )
        byOperationId[operationId] = StoredTransfer(transfer, fingerprint)
        operationIdByTransferId[transfer.transferId] = operationId
        appendEvent(JournalEventType.TRANSFER_COMMITTED, transfer)
        SubmitResult.Committed(transfer)
    }

    fun findByOperationId(operationId: String): TransferDto? = synchronized(lock) {
        byOperationId[operationId]?.transfer
    }

    fun findByTransferId(transferId: String): TransferDto? = synchronized(lock) {
        operationIdByTransferId[transferId]?.let(byOperationId::get)?.transfer
    }

    fun updateStatus(operationId: String, status: TransferStatus): TransferDto? = synchronized(lock) {
        val stored = byOperationId[operationId] ?: return@synchronized null
        if (stored.transfer.status == status) return@synchronized stored.transfer

        val updated = stored.transfer.copy(
            status = status,
            updatedAt = clock.instant().toString(),
        )
        byOperationId[operationId] = stored.copy(transfer = updated)
        appendEvent(JournalEventType.STATUS_CHANGED, updated)
        updated
    }

    fun journal(): List<JournalEntryDto> = synchronized(lock) { journal.toList() }

    fun size(): Int = synchronized(lock) { byOperationId.size }

    fun clear() = synchronized(lock) {
        byOperationId.clear()
        operationIdByTransferId.clear()
        journal.clear()
        nextSequence = 1L
    }

    private fun appendEvent(type: JournalEventType, operation: TransferDto) {
        journal += JournalEntryDto(
            sequence = nextSequence++,
            type = type,
            operationId = operation.operationId,
            transferId = operation.transferId,
            status = operation.status,
            recordedAt = clock.instant().toString(),
        )
    }

    private data class StoredTransfer(
        val transfer: TransferDto,
        val payloadFingerprint: String,
    )
}

sealed interface SubmitResult {
    val transfer: TransferDto

    data class Committed(override val transfer: TransferDto) : SubmitResult
    data class Replay(override val transfer: TransferDto) : SubmitResult
    data class Conflict(override val transfer: TransferDto) : SubmitResult
}

private fun CreateTransferRequest.fingerprint(): String {
    val canonicalPayload = buildString {
        append(fromAccountId)
        append('\u0000')
        append(toAccountId)
        append('\u0000')
        append(amountMinorUnits)
        append('\u0000')
        append(currency)
        append('\u0000')
        append(reference.orEmpty())
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
