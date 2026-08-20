package com.bank.mobile.feature.scheduled

import com.bank.mobile.core.ids.OperationId
import com.bank.mobile.core.time.EpochClock
import com.bank.mobile.feature.transfer.TransferDraft
import com.bank.mobile.testing.FixedOperationIds
import com.bank.mobile.testing.transferDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledStoreTest {
    @Test fun createsScheduleWithGeneratedIdentity() = runTest {
        val repository = FakeScheduledRepository()
        val store = ScheduledStore(
            repository = repository,
            operationIds = FixedOperationIds("schedule-seed"),
            scope = backgroundScope,
        )

        store.schedule(transferDraft(), 60_000)
        runCurrent()

        assertEquals(listOf("schedule-schedule-seed"), repository.created)
    }

    @Test fun manualRunUsesCurrentDeviceTime() = runTest {
        val repository = FakeScheduledRepository()
        val store = ScheduledStore(
            repository = repository,
            operationIds = FixedOperationIds("unused"),
            scope = backgroundScope,
            deviceClock = EpochClock { 42_000 },
        )

        store.runDue()
        runCurrent()

        assertEquals(listOf(42_000L), repository.runTimes)
    }
}

private class FakeScheduledRepository : ScheduledRepository {
    val created = mutableListOf<String>()
    val runTimes = mutableListOf<Long>()
    override fun observe(): Flow<List<ScheduledPayment>> = MutableStateFlow(emptyList())
    override suspend fun schedule(scheduleId: String, draft: TransferDraft, delayFromDeviceMillis: Long): ScheduledPayment {
        created += scheduleId
        return ScheduledPayment(scheduleId, draft, delayFromDeviceMillis, OperationId("scheduled-$scheduleId"), ScheduledStatus.QUEUED)
    }
    override suspend fun submitDue(nowEpochMillis: Long): ScheduledRunSummary {
        runTimes += nowEpochMillis
        return ScheduledRunSummary(due = 0, submitted = 0, unresolved = 0, failed = 0)
    }
}
