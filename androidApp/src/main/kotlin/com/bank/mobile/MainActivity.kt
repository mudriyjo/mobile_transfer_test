package com.bank.mobile

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.bank.mobile.ui.AndroidBankApp

class MainActivity : FragmentActivity() {
    private var deepLinkRequest: AndroidDeepLinkRequest? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        forwardDeepLink(intent)
        setContent {
            AndroidBankApp(
                deepLinkUrl = deepLinkRequest?.rawUrl,
                deepLinkRequestId = deepLinkRequest?.id ?: 0,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardDeepLink(intent)
    }

    private fun forwardDeepLink(intent: Intent?) {
        val rawUrl = intent?.dataString ?: return
        deepLinkRequest = (application as MobileBankApplication).receiveDeepLink(rawUrl)
    }
}
