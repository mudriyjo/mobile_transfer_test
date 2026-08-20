package com.bank.mobile.core.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessStateTest {
    @Test fun terminatedStateIsDistinctFromBackground() {
        assertTrue(ProcessState.TERMINATED != ProcessState.BACKGROUND)
    }

    @Test fun recorderProducesOrderedRestorationSnapshot() {
        var now = 1_000L
        val recorder = LifecycleStateRecorder(
            initialState = ProcessState.FOREGROUND,
            clock = { now++ },
        )

        recorder.record(ProcessState.BACKGROUND)
        recorder.record(ProcessState.SUSPENDED)
        val foreground = recorder.record(ProcessState.FOREGROUND)
        val snapshot = recorder.snapshot()

        assertEquals(listOf(1L, 2L, 3L), recorder.transitions.value.map { it.sequence })
        assertEquals(1_002L, foreground.observedAtEpochMillis)
        assertTrue(snapshot.isRestorationRequired)
        assertEquals(3L, snapshot.transitionCount)
    }

    @Test fun recorderKeepsBoundedHistoryWithoutResettingSequence() {
        val recorder = LifecycleStateRecorder(
            initialState = ProcessState.BACKGROUND,
            clock = { 100L },
            historyLimit = 2,
        )

        recorder.record(ProcessState.FOREGROUND)
        recorder.record(ProcessState.BACKGROUND)
        recorder.record(ProcessState.FOREGROUND)

        assertEquals(listOf(2L, 3L), recorder.transitions.value.map { it.sequence })
        assertEquals(3L, recorder.snapshot().transitionCount)
    }

    @Test fun terminalProcessCannotReturnToForegroundAsExpectedTransition() {
        val recorder = LifecycleStateRecorder(ProcessState.TERMINATED, clock = { 0L })
        val transition = recorder.record(ProcessState.FOREGROUND)

        assertFalse(transition.expected)
        assertTrue(recorder.state.value.isInteractive)
    }
}
