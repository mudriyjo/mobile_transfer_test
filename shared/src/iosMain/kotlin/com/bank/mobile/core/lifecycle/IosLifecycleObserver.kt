@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bank.mobile.core.lifecycle

import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification

class IosLifecycleObserver : AppLifecycleObserver {
    private val notificationCenter = NSNotificationCenter.defaultCenter
    private val recorder = LifecycleStateRecorder(ProcessState.BACKGROUND)
    override val state: StateFlow<ProcessState> = recorder.state
    override val transitions: StateFlow<List<LifecycleTransition>> = recorder.transitions
    private val observerTokens = listOf(
        observe(UIApplicationDidBecomeActiveNotification) { ProcessState.FOREGROUND },
        observe(UIApplicationWillResignActiveNotification) { ProcessState.BACKGROUND },
        observe(UIApplicationDidEnterBackgroundNotification) { ProcessState.SUSPENDED },
        observe(UIApplicationWillTerminateNotification) { ProcessState.TERMINATED },
    )

    private fun observe(name: String?, stateProvider: () -> ProcessState): Any =
        notificationCenter.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
            recorder.record(stateProvider())
        }

    override fun close() {
        observerTokens.forEach(notificationCenter::removeObserver)
        recorder.record(ProcessState.TERMINATED, LifecycleTransitionTrigger.OBSERVER_CLOSED)
    }
}
