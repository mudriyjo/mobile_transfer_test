package com.bank.mobile.core.ids

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.jvm.JvmInline

@JvmInline
value class OperationId(val value: String) {
    init { require(value.isNotBlank()) }
}

fun interface OperationIdProvider {
    fun next(): OperationId
}

class RandomOperationIdProvider(
    private val random: Random = Random.Default,
) : OperationIdProvider {
    override fun next(): OperationId = OperationId(
        "op-${Clock.System.now().toEpochMilliseconds().toString(16)}-${random.nextLong().toULong().toString(16)}",
    )
}
