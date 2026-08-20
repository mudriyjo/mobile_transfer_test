package com.bank.mobile.ui

import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.bank.mobile.core.lifecycle.AndroidLifecycleObserver
import com.bank.mobile.core.lifecycle.AppLifecycleObserver
import com.bank.mobile.core.network.AndroidHttpClientEngineFactory
import com.bank.mobile.core.network.AndroidNetworkMonitor
import com.bank.mobile.core.network.NetworkMonitor
import com.bank.mobile.core.network.PlatformHttpClientEngineFactory
import com.bank.mobile.core.security.AndroidBiometricAuthenticator
import com.bank.mobile.core.security.AndroidScreenSecurity
import com.bank.mobile.core.security.BiometricAuthenticator
import com.bank.mobile.core.security.SensitiveScreenController
import com.bank.mobile.core.storage.AndroidDatabaseDriverFactory
import com.bank.mobile.core.storage.AndroidSecureStorage
import com.bank.mobile.core.storage.DatabaseDriverFactory
import com.bank.mobile.core.storage.SecureStorage
import com.bank.mobile.di.sharedModule
import com.bank.mobile.feature.accounts.AccountsStore
import com.bank.mobile.feature.beneficiaries.BeneficiaryStore
import com.bank.mobile.feature.scheduled.ScheduledStore
import com.bank.mobile.feature.transfer.TransferViewModel
import com.bank.mobile.feature.transfer.TransferDeepLinkHandler
import kotlinx.coroutines.MainScope
import org.koin.core.KoinApplication
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose

@Composable
fun AndroidBankApp(
    deepLinkUrl: String? = null,
    deepLinkRequestId: Long = 0,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
        ?: error("AndroidBankApp must be hosted by a FragmentActivity")
    val graph = remember(activity) { AndroidUiGraph(context.applicationContext, activity) }
    DisposableEffect(graph) {
        onDispose(graph::close)
    }
    LaunchedEffect(graph, deepLinkRequestId) {
        deepLinkUrl?.let(graph::openDeepLink)
    }
    App(graph.coordinator)
}

private class AndroidUiGraph(
    context: Context,
    activity: FragmentActivity,
) {
    private val screenScope = MainScope()
    private val application: KoinApplication = koinApplication {
        modules(
            module {
                single<SecureStorage> { AndroidSecureStorage(context) }
                single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(context) }
                single<PlatformHttpClientEngineFactory> { AndroidHttpClientEngineFactory() }
                single<NetworkMonitor> { AndroidNetworkMonitor(context) } onClose { it?.close() }
                single<BiometricAuthenticator> { AndroidBiometricAuthenticator(activity) }
                single<SensitiveScreenController> { AndroidScreenSecurity(activity) }
                single<AppLifecycleObserver> { AndroidLifecycleObserver() }
            },
            sharedModule(baseUrl = "http://10.0.2.2:8080"),
        )
    }
    private val transferViewModel = application.koin.get<TransferViewModel> {
        parametersOf(screenScope)
    }
    private val deepLinks = TransferDeepLinkHandler(transferViewModel)
    private val accountsStore = application.koin.get<AccountsStore> { parametersOf(screenScope) }
    private val scheduledStore = application.koin.get<ScheduledStore> { parametersOf(screenScope) }
    private val beneficiaryStore = application.koin.get<BeneficiaryStore> { parametersOf(screenScope) }
    private val lifecycleObserver = application.koin.get<AppLifecycleObserver>()
    val coordinator = BankUiCoordinator(
        transferViewModel = transferViewModel,
        accountsStore = accountsStore,
        scheduledStore = scheduledStore,
        beneficiaryStore = beneficiaryStore,
        appLifecycleObserver = lifecycleObserver,
    )

    fun openDeepLink(rawUrl: String) {
        deepLinks.open(rawUrl)
    }

    fun close() {
        coordinator.close()
        lifecycleObserver.close()
        application.close()
    }
}
