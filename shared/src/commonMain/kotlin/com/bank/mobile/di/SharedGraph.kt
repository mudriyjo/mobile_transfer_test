package com.bank.mobile.di

import app.cash.sqldelight.db.SqlDriver
import com.bank.mobile.core.analytics.AnalyticsTracker
import com.bank.mobile.core.analytics.NoOpAnalyticsTracker
import com.bank.mobile.core.ids.OperationIdProvider
import com.bank.mobile.core.ids.RandomOperationIdProvider
import com.bank.mobile.core.network.ApiErrorMapper
import com.bank.mobile.core.network.BankApi
import com.bank.mobile.core.network.KtorBankApi
import com.bank.mobile.core.network.PlatformHttpClientEngineFactory
import com.bank.mobile.core.security.SessionManager
import com.bank.mobile.core.storage.DatabaseDriverFactory
import com.bank.mobile.db.MobileBankDatabase
import com.bank.mobile.feature.accounts.AccountsRepository
import com.bank.mobile.feature.accounts.AccountsRepositoryImpl
import com.bank.mobile.feature.accounts.AccountsStore
import com.bank.mobile.feature.beneficiaries.BeneficiaryRepository
import com.bank.mobile.feature.beneficiaries.BeneficiaryRepositoryImpl
import com.bank.mobile.feature.beneficiaries.BeneficiaryStore
import com.bank.mobile.feature.scheduled.ScheduledRepository
import com.bank.mobile.feature.scheduled.ScheduledRepositoryImpl
import com.bank.mobile.feature.scheduled.ScheduledStore
import com.bank.mobile.feature.transfer.CreateTransferUseCase
import com.bank.mobile.feature.transfer.ReconcileTransferUseCase
import com.bank.mobile.feature.transfer.TransferLocalDataSource
import com.bank.mobile.feature.transfer.TransferRemoteDataSource
import com.bank.mobile.feature.transfer.TransferRepository
import com.bank.mobile.feature.transfer.TransferRepositoryImpl
import com.bank.mobile.feature.transfer.TransferViewModel
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.dsl.onClose

fun sharedModule(baseUrl: String): Module = module {
    single<OperationIdProvider> { RandomOperationIdProvider() }
    single<AnalyticsTracker> { NoOpAnalyticsTracker }
    single { ApiErrorMapper() }
    single { SessionManager(get()) }
    single<SqlDriver> { get<DatabaseDriverFactory>().createDriver() } onClose { it?.close() }
    single { MobileBankDatabase(get()) }
    single<BankApi> {
        val sessionManager = get<SessionManager>()
        KtorBankApi(
            engine = get<PlatformHttpClientEngineFactory>().create(),
            baseUrl = baseUrl,
            sessionToken = sessionManager::bearerToken,
            errorMapper = get(),
        )
    } onClose { it?.close() }

    single<AccountsRepository> { AccountsRepositoryImpl(get(), get()) }
    single<BeneficiaryRepository> { BeneficiaryRepositoryImpl(get(), get()) }
    single { TransferLocalDataSource(get()) }
    single { TransferRemoteDataSource(get()) }
    single<TransferRepository> { TransferRepositoryImpl(get(), get(), get()) }
    single<ScheduledRepository> { ScheduledRepositoryImpl(get(), get<TransferRepository>()) }
    factory { CreateTransferUseCase(get()) }
    factory { ReconcileTransferUseCase(get()) }

    factory { parameters -> AccountsStore(get(), parameters.get<CoroutineScope>()) }
    factory { parameters -> BeneficiaryStore(get(), parameters.get<CoroutineScope>()) }
    factory {
        parameters -> ScheduledStore(get(), get(), parameters.get<CoroutineScope>())
    }
    factory { parameters ->
        TransferViewModel(
            createTransfer = get(),
            reconcileTransfer = get(),
            operationIds = get(),
            biometricAuthenticator = get(),
            analytics = get(),
            scope = parameters.get(),
        )
    }
}
