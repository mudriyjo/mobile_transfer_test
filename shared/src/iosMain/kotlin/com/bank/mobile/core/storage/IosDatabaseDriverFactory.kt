package com.bank.mobile.core.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.bank.mobile.db.MobileBankDatabase

class IosDatabaseDriverFactory : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = MobileBankDatabase.Schema,
        name = "mobile-bank.db",
    )
}
