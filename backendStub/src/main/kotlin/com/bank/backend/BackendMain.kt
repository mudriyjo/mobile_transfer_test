package com.bank.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val controlsEnabled = System.getenv("BACKEND_CONTROLS_ENABLED")
        ?.equals("false", ignoreCase = true) != true

    embeddedServer(
        factory = Netty,
        host = "0.0.0.0",
        port = port,
    ) {
        bankBackendModule(
            BankBackendEnvironment(controlsEnabled = controlsEnabled),
        )
    }.start(wait = true)
}
