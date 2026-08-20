package com.bank.mobile.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

class IosHttpClientEngineFactory : PlatformHttpClientEngineFactory {
    override fun create(): HttpClientEngine = Darwin.create()
}
