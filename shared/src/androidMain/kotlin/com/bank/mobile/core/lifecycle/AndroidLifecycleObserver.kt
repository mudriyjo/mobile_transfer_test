package com.bank.mobile.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.StateFlow

class AndroidLifecycleObserver : AppLifecycleObserver, DefaultLifecycleObserver {
    private val lifecycle = ProcessLifecycleOwner.get().lifecycle
    private val recorder = LifecycleStateRecorder(
        initialState = if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            ProcessState.FOREGROUND
        } else {
            ProcessState.BACKGROUND
        },
    )
    override val state: StateFlow<ProcessState> = recorder.state
    override val transitions: StateFlow<List<LifecycleTransition>> = recorder.transitions

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        recorder.record(ProcessState.FOREGROUND)
    }

    override fun onStop(owner: LifecycleOwner) {
        recorder.record(ProcessState.BACKGROUND)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        recorder.record(ProcessState.TERMINATED)
    }

    override fun close() {
        lifecycle.removeObserver(this)
        recorder.record(ProcessState.TERMINATED, LifecycleTransitionTrigger.OBSERVER_CLOSED)
    }
}
