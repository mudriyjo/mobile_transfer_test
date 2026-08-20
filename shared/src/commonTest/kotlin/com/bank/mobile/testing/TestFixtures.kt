package com.bank.mobile.testing

import com.bank.mobile.core.analytics.AnalyticsEvent
import com.bank.mobile.core.analytics.AnalyticsTracker
import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.ids.OperationIdProvider
import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import com.bank.mobile.core.network.NetworkMonitor
import com.bank.mobile.core.security.BiometricAuthenticator
import com.bank.mobile.core.security.BiometricResult
import com.bank.mobile.feature.transfer.ReconcileSummary
import com.bank.mobile.feature.transfer.TransferDraft
import com.bank.mobile.feature.transfer.TransferFlowKind
import com.bank.mobile.feature.transfer.TransferHistoryFilter
import com.bank.mobile.feature.transfer.TransferHistorySnapshot
import com.bank.mobile.feature.transfer.TransferRecord
import com.bank.mobile.feature.transfer.TransferRepository
import com.bank.mobile.feature.transfer.TransferStatus
import com.bank.mobile.feature.transfer.TransferStatusRefreshResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FixedOperationIds(vararg values: String) : OperationIdProvider {
    private val ids = ArrayDeque(values.map(::OperationId))
    override fun next(): OperationId = ids.removeFirst()
}

class FakeNetworkMonitor(online: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(online)
}

class FakeBiometricAuthenticator(
    var result: BiometricResult = BiometricResult.Success,
) : BiometricAuthenticator {
    override suspend fun authenticate(reason: String): BiometricResult = result
}

class RecordingAnalytics : AnalyticsTracker {
    val events = mutableListOf<AnalyticsEvent>()
    override fun track(event: AnalyticsEvent) { events += event }
}

class FakeTransferRepository(
    var result: TransferRecord = transferRecord(),
    var failure: Throwable? = null,
    var observedResult: TransferRecord? = result,
    var refreshResult: TransferStatusRefreshResult? = null,
) : TransferRepository {
    val submittedIds = mutableListOf<OperationId>()
    var reconcileCalls = 0

    override suspend fun createTransfer(operationId: OperationId, draft: TransferDraft): TransferRecord {
        submittedIds += operationId
        failure?.let { throw it }
        return result.copy(operationId = operationId, draft = draft)
    }

    override fun observe(operationId: OperationId): Flow<TransferRecord?> = MutableStateFlow(observedResult)
    override fun observeHistory(): Flow<List<TransferRecord>> =
        MutableStateFlow(listOfNotNull(observedResult))

    override suspend fun find(operationId: OperationId): TransferRecord? =
        observedResult?.takeIf { it.operationId == operationId }

    override suspend fun history(filter: TransferHistoryFilter): TransferHistorySnapshot =
        TransferHistorySnapshot(listOfNotNull(observedResult), filter)

    override suspend fun refreshStatus(operationId: OperationId): TransferStatusRefreshResult =
        refreshResult ?: observedResult
            ?.let { TransferStatusRefreshResult.Unchanged(operationId, it) }
            ?: TransferStatusRefreshResult.NotTracked(operationId)

    override suspend fun reconcilePending(): ReconcileSummary {
        reconcileCalls += 1
        return ReconcileSummary(0, 0, 0)
    }
}

fun transferDraft() = TransferDraft(
    fromAccountId = "account-everyday",
    beneficiaryId = "beneficiary-alex",
    amount = Money(1_250, CurrencyCode("EUR")),
    reference = "Dinner",
)

fun transferRecord() = TransferRecord(
    operationId = OperationId("operation-fixed"),
    remoteTransferId = "transfer-fixed",
    flowKind = TransferFlowKind.INSTANT,
    draft = transferDraft(),
    payloadFingerprint = transferDraft().fingerprint(),
    status = TransferStatus.COMPLETED,
    serverStatus = TransferStatus.COMPLETED,
    createdAtEpochMillis = 100,
    updatedAtEpochMillis = 101,
    attemptCount = 1,
)
