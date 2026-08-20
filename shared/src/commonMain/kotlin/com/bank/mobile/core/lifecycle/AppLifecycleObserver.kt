package com.bank.mobile.core.lifecycle

import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProcessState {
    FOREGROUND,
    BACKGROUND,
    SUSPENDED,
    TERMINATED,
    ;

    val isInteractive: Boolean
        get() = this == FOREGROUND

    val mayExecuteCode: Boolean
        get() = this == FOREGROUND || this == BACKGROUND

    val isTerminal: Boolean
        get() = this == TERMINATED
}

enum class LifecycleTransitionTrigger {
    INITIAL_SNAPSHOT,
    PLATFORM_CALLBACK,
    APP_BRIDGE,
    OBSERVER_CLOSED,
}

data class LifecycleTransition(
    val sequence: Long,
    val from: ProcessState,
    val to: ProcessState,
    val trigger: LifecycleTransitionTrigger,
    val observedAtEpochMillis: Long,
    val expected: Boolean,
) {
    val changedState: Boolean
        get() = from != to
}

data class ProcessLifecycleSnapshot(
    val currentState: ProcessState,
    val lastTransition: LifecycleTransition?,
    val transitionCount: Long,
) {
    val isRestorationRequired: Boolean
        get() = lastTransition?.let { transition ->
            transition.to == ProcessState.FOREGROUND &&
                transition.from in setOf(ProcessState.BACKGROUND, ProcessState.SUSPENDED)
        } == true
}

interface AppLifecycleObserver {
    val state: StateFlow<ProcessState>
    val transitions: StateFlow<List<LifecycleTransition>>

    fun snapshot(): ProcessLifecycleSnapshot = ProcessLifecycleSnapshot(
        currentState = state.value,
        lastTransition = transitions.value.lastOrNull(),
        transitionCount = transitions.value.lastOrNull()?.sequence ?: 0,
    )

    fun close()
}

/** Converts platform callbacks into an ordered, bounded transition history. */
class LifecycleStateRecorder(
    initialState: ProcessState,
    private val clock: EpochClock = DeviceEpochClock,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    private val mutableState = MutableStateFlow(initialState)
    private val mutableTransitions = MutableStateFlow<List<LifecycleTransition>>(emptyList())
    private var sequence = 0L

    val state: StateFlow<ProcessState> = mutableState.asStateFlow()
    val transitions: StateFlow<List<LifecycleTransition>> = mutableTransitions.asStateFlow()

    init {
        require(historyLimit > 0) { "historyLimit must be positive" }
    }

    fun record(
        newState: ProcessState,
        trigger: LifecycleTransitionTrigger = LifecycleTransitionTrigger.PLATFORM_CALLBACK,
    ): LifecycleTransition {
        val previous = mutableState.value
        sequence += 1
        val transition = LifecycleTransition(
            sequence = sequence,
            from = previous,
            to = newState,
            trigger = trigger,
            observedAtEpochMillis = clock.nowMillis(),
            expected = isExpectedTransition(previous, newState),
        )
        mutableState.value = newState
        mutableTransitions.value = (mutableTransitions.value + transition).takeLast(historyLimit)
        return transition
    }

    fun snapshot(): ProcessLifecycleSnapshot = ProcessLifecycleSnapshot(
        currentState = state.value,
        lastTransition = transitions.value.lastOrNull(),
        transitionCount = sequence,
    )

    private fun isExpectedTransition(from: ProcessState, to: ProcessState): Boolean = when (from) {
        ProcessState.FOREGROUND -> to in setOf(
            ProcessState.FOREGROUND,
            ProcessState.BACKGROUND,
            ProcessState.SUSPENDED,
            ProcessState.TERMINATED,
        )

        ProcessState.BACKGROUND -> to in setOf(
            ProcessState.BACKGROUND,
            ProcessState.FOREGROUND,
            ProcessState.SUSPENDED,
            ProcessState.TERMINATED,
        )

        ProcessState.SUSPENDED -> to in setOf(
            ProcessState.SUSPENDED,
            ProcessState.BACKGROUND,
            ProcessState.FOREGROUND,
            ProcessState.TERMINATED,
        )

        ProcessState.TERMINATED -> to == ProcessState.TERMINATED
    }

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = 32
    }
}
