package com.bank.mobile.core.time

import kotlin.time.Clock

fun interface EpochClock {
    fun nowMillis(): Long
}

object DeviceEpochClock : EpochClock {
    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
