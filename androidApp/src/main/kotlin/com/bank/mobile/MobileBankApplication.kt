package com.bank.mobile

import android.app.Application

data class AndroidDeepLinkRequest(
    val id: Long,
    val rawUrl: String,
)

class MobileBankApplication : Application() {
    private var nextDeepLinkId = 0L

    var latestDeepLink: AndroidDeepLinkRequest? = null
        private set

    fun receiveDeepLink(rawUrl: String): AndroidDeepLinkRequest {
        nextDeepLinkId += 1
        return AndroidDeepLinkRequest(nextDeepLinkId, rawUrl).also { latestDeepLink = it }
    }
}
