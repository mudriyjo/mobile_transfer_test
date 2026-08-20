package com.bank.mobile.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import com.bank.mobile.core.lifecycle.AppLifecycleObserver
import com.bank.mobile.core.lifecycle.IosLifecycleObserver
import com.bank.mobile.core.network.IosHttpClientEngineFactory
import com.bank.mobile.core.network.IosNetworkMonitor
import com.bank.mobile.core.network.NetworkMonitor
import com.bank.mobile.core.network.PlatformHttpClientEngineFactory
import com.bank.mobile.core.security.BiometricAuthenticator
import com.bank.mobile.core.security.IosBiometricAuthenticator
import com.bank.mobile.core.storage.DatabaseDriverFactory
import com.bank.mobile.core.storage.IosDatabaseDriverFactory
import com.bank.mobile.core.storage.IosSecureStorage
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
import platform.UIKit.UIViewController

class IosAppBridge {
    private var activeGraph: IosUiGraph? = null
    private var pendingUrl: String? = null

    fun makeViewController(): UIViewController {
        val graph = IosUiGraph()
        activeGraph = graph
        pendingUrl?.let { rawUrl ->
            pendingUrl = null
            graph.openDeepLink(rawUrl)
        }
        return ComposeUIViewController {
            DisposableEffect(graph) {
                onDispose {
                    if (activeGraph === graph) activeGraph = null
                    graph.close()
                }
            }
            App(graph.coordinator)
        }
    }

    fun openUrl(rawUrl: String) {
        val graph = activeGraph
        if (graph == null) pendingUrl = rawUrl else graph.openDeepLink(rawUrl)
    }
}

fun MainViewController(): UIViewController = IosAppBridge().makeViewController()

private class IosUiGraph {
    private val screenScope = MainScope()
    private val application: KoinApplication = koinApplication {
        modules(
            module {
                single<SecureStorage> { IosSecureStorage() }
                single<DatabaseDriverFactory> { IosDatabaseDriverFactory() }
                single<PlatformHttpClientEngineFactory> { IosHttpClientEngineFactory() }
                single<NetworkMonitor> { IosNetworkMonitor() } onClose { it?.close() }
                single<BiometricAuthenticator> { IosBiometricAuthenticator() }
                single<AppLifecycleObserver> { IosLifecycleObserver() }
            },
            sharedModule(baseUrl = "http://127.0.0.1:8080"),
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
