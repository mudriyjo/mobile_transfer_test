package com.bank.mobile.feature.scheduled

import com.bank.mobile.core.ids.OperationIdProvider
import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import com.bank.mobile.feature.transfer.TransferDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduledState(
    val payments: List<ScheduledPayment> = emptyList(),
    val isCreating: Boolean = false,
    val isRunningDue: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

private data class ScheduledActionState(
    val isCreating: Boolean = false,
    val isRunningDue: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

class ScheduledStore(
    private val repository: ScheduledRepository,
    private val operationIds: OperationIdProvider,
    private val scope: CoroutineScope,
    private val deviceClock: EpochClock = DeviceEpochClock,
) {
    val payments: StateFlow<List<ScheduledPayment>> = repository.observe()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val actions = MutableStateFlow(ScheduledActionState())

    val state: StateFlow<ScheduledState> = combine(payments, actions) { payments, action ->
        ScheduledState(
            payments = payments,
            isCreating = action.isCreating,
            isRunningDue = action.isRunningDue,
            notice = action.notice,
            error = action.error,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ScheduledState())

    fun schedule(draft: TransferDraft, delayMillis: Long) {
        require(delayMillis >= 0) { "Schedule delay cannot be negative" }
        if (actions.value.isCreating) return
        actions.value = actions.value.copy(isCreating = true, notice = null, error = null)
        scope.launch {
            try {
                val scheduleId = "schedule-${operationIds.next().value}"
                repository.schedule(scheduleId, draft, delayMillis)
                actions.value = actions.value.copy(
                    isCreating = false,
                    notice = "Payment scheduled",
                    error = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actions.value = actions.value.copy(
                    isCreating = false,
                    notice = null,
                    error = "Unable to schedule payment",
                )
            }
        }
    }

    fun runDue(nowEpochMillis: Long = deviceClock.nowMillis()) {
        if (actions.value.isRunningDue) return
        actions.value = actions.value.copy(isRunningDue = true, notice = null, error = null)
        scope.launch {
            try {
                val result = repository.submitDue(nowEpochMillis)
                actions.value = when {
                    result.unresolved > 0 -> actions.value.copy(
                        isRunningDue = false,
                        notice = "Some payment statuses still need confirmation",
                        error = null,
                    )
                    result.failed > 0 -> actions.value.copy(
                        isRunningDue = false,
                        notice = null,
                        error = "Some due payments could not be submitted",
                    )
                    result.due == 0 -> actions.value.copy(
                        isRunningDue = false,
                        notice = "No payments are due",
                        error = null,
                    )
                    else -> actions.value.copy(
                        isRunningDue = false,
                        notice = "Submitted ${result.submitted} due payment(s)",
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actions.value = actions.value.copy(
                    isRunningDue = false,
                    notice = null,
                    error = "Unable to check due payments",
                )
            }
        }
    }

    fun clearFeedback() {
        actions.value = actions.value.copy(notice = null, error = null)
    }
}
