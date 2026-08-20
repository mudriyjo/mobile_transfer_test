package com.bank.mobile.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

class AndroidHttpClientEngineFactory : PlatformHttpClientEngineFactory {
    override fun create(): HttpClientEngine = OkHttp.create()
}
