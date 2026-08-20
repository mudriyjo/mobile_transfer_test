package com.bank.backend

import kotlinx.coroutines.CompletableDeferred

class FaultController(initialPlan: FaultPlan = FaultPlan()) {
    private val lock = Any()
    private var plan = initialPlan.validated()
    private var submitModeApplicationsRemaining = remainingApplications(plan)
    private var blockGate = CompletableDeferred<Unit>()
    private var blockedSubmissions = 0
    private val statusChecks = mutableMapOf<String, Int>()

    fun configure(newPlan: FaultPlan): FaultStateDto {
        val validated = newPlan.validated()
        synchronized(lock) {
            blockGate.complete(Unit)
            plan = validated
            submitModeApplicationsRemaining = remainingApplications(validated)
            blockGate = CompletableDeferred()
            blockedSubmissions = 0
            statusChecks.clear()
            return snapshotLocked()
        }
    }

    fun reset(): FaultStateDto = configure(FaultPlan())

    fun snapshot(): FaultStateDto = synchronized(lock) { snapshotLocked() }

    fun consumeSubmitMode(): SubmitFaultMode = synchronized(lock) {
        if (plan.submitMode == SubmitFaultMode.NORMAL || submitModeApplicationsRemaining == 0) {
            return@synchronized SubmitFaultMode.NORMAL
        }

        submitModeApplicationsRemaining -= 1
        plan.submitMode
    }

    fun submitDelayMillis(): Long = synchronized(lock) { plan.submitDelayMillis }

    fun terminalStatusImmediatelyAfterCommit(): TransferStatus? = synchronized(lock) {
        plan.terminalStatus.takeIf { plan.completeAfterSuccessfulStatusChecks == 0 }
    }

    fun nextStatusDecision(operationId: String, currentStatus: TransferStatus): StatusDecision = synchronized(lock) {
        if (currentStatus != TransferStatus.PROCESSING) {
            return@synchronized StatusDecision.ReturnCurrent
        }

        val checkNumber = (statusChecks[operationId] ?: 0) + 1
        statusChecks[operationId] = checkNumber
        if (checkNumber <= plan.statusFailuresBeforeSuccess) {
            return@synchronized StatusDecision.TemporaryFailure
        }

        val successfulCheckNumber = checkNumber - plan.statusFailuresBeforeSuccess
        if (successfulCheckNumber >= plan.completeAfterSuccessfulStatusChecks) {
            StatusDecision.ChangeStatus(plan.terminalStatus)
        } else {
            StatusDecision.ReturnCurrent
        }
    }

    suspend fun awaitSubmissionRelease() {
        val gate = synchronized(lock) {
            blockedSubmissions += 1
            blockGate
        }
        try {
            gate.await()
        } finally {
            synchronized(lock) {
                blockedSubmissions = (blockedSubmissions - 1).coerceAtLeast(0)
            }
        }
    }

    fun releaseBlockedSubmissions(): Int {
        val released = synchronized(lock) {
            val count = blockedSubmissions
            blockGate.complete(Unit)
            blockGate = CompletableDeferred()
            count
        }
        return released
    }

    private fun snapshotLocked() = FaultStateDto(
        plan = plan,
        submitModeApplicationsRemaining = submitModeApplicationsRemaining,
        blockedSubmissions = blockedSubmissions,
    )

    private companion object {
        fun remainingApplications(plan: FaultPlan): Int =
            if (plan.submitMode == SubmitFaultMode.NORMAL) 0 else plan.submitModeApplications
    }
}

sealed interface StatusDecision {
    data object TemporaryFailure : StatusDecision
    data object ReturnCurrent : StatusDecision
    data class ChangeStatus(val status: TransferStatus) : StatusDecision
}

private fun FaultPlan.validated(): FaultPlan {
    require(submitModeApplications in 0..100) {
        "submitModeApplications must be between 0 and 100"
    }
    require(submitDelayMillis in 0..60_000) {
        "submitDelayMillis must be between 0 and 60000"
    }
    require(statusFailuresBeforeSuccess in 0..100) {
        "statusFailuresBeforeSuccess must be between 0 and 100"
    }
    require(completeAfterSuccessfulStatusChecks in 0..100) {
        "completeAfterSuccessfulStatusChecks must be between 0 and 100"
    }
    require(terminalStatus != TransferStatus.PROCESSING) {
        "terminalStatus must be COMPLETED or REJECTED"
    }
    return this
}
