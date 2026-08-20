package com.bank.mobile.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bank.mobile.db.MobileBankDatabase

fun testDatabase(): Pair<MobileBankDatabase, JdbcSqliteDriver> {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    MobileBankDatabase.Schema.create(driver)
    return MobileBankDatabase(driver) to driver
}
