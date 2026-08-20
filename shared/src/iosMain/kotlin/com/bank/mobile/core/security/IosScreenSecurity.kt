@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bank.mobile.core.security

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIWindow

/** Prevents sensitive content from appearing in the app-switcher snapshot. */
class IosScreenSecurity(
    private val window: UIWindow,
) : SensitiveScreenController {
    private val notificationCenter = NSNotificationCenter.defaultCenter
    private val privacyCover = UIView(frame = window.bounds).apply {
        backgroundColor = UIColor.blackColor
        autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        hidden = true
    }
    private var secure = false
    override val isSecure: Boolean
        get() = secure
    private val resignToken = notificationCenter.addObserverForName(
        name = UIApplicationWillResignActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) {
        if (secure) privacyCover.hidden = false
    }
    private val activeToken = notificationCenter.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) {
        privacyCover.hidden = true
    }

    init {
        window.addSubview(privacyCover)
    }

    override fun setSecure(enabled: Boolean) {
        secure = enabled
        if (!enabled) privacyCover.hidden = true
    }

    fun close() {
        notificationCenter.removeObserver(resignToken)
        notificationCenter.removeObserver(activeToken)
        privacyCover.removeFromSuperview()
    }
}
