package com.bank.mobile.core.security

import android.app.Activity
import android.view.WindowManager

class AndroidScreenSecurity(
    private val activity: Activity,
) : SensitiveScreenController {
    private var secure = false
    override val isSecure: Boolean
        get() = secure

    override fun setSecure(enabled: Boolean) {
        secure = enabled
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
