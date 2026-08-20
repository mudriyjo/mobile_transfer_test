package com.bank.mobile.feature.accounts

import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import com.bank.mobile.core.time.EpochClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsStoreTest {
    @Test
    fun cachedAccountsArePublishedWithFreshnessFromPersistedTimestamps() = runTest {
        val repository = FakeAccountsRepository(
            listOf(
                account(id = "old", updatedAt = 899),
                account(id = "recent", updatedAt = 950),
            ),
        )
        val store = AccountsStore(
            repository = repository,
            scope = backgroundScope,
            clock = EpochClock { 1_000 },
            cacheMaxAgeMillis = 100,
        )

        runCurrent()

        assertEquals(AccountsCacheStatus.AVAILABLE, store.state.value.cacheStatus)
        assertEquals(899L, store.state.value.oldestUpdateEpochMillis)
        assertTrue(store.state.value.isStale("old"))
        assertFalse(store.state.value.isStale("recent"))
    }

    @Test
    fun emptyCacheIsDistinctFromInitialLoading() = runTest {
        val store = AccountsStore(FakeAccountsRepository(emptyList()), backgroundScope)

        assertTrue(store.state.value.isInitialLoading)
        runCurrent()

        assertEquals(AccountsCacheStatus.EMPTY, store.state.value.cacheStatus)
        assertFalse(store.state.value.isInitialLoading)
    }

    @Test
    fun refreshGuardIgnoresConcurrentRequests() = runTest {
        val repository = FakeAccountsRepository(listOf(account()))
        repository.refreshGate = CompletableDeferred()
        val store = AccountsStore(repository, backgroundScope)
        runCurrent()

        store.refresh()
        store.refresh()
        runCurrent()

        assertEquals(1, repository.refreshes)
        assertTrue(store.state.value.refreshing)

        repository.refreshGate?.complete(Unit)
        runCurrent()

        assertFalse(store.state.value.refreshing)
    }

    @Test
    fun refreshFailureRetainsCacheAndUpdatesStateAndEffect() = runTest {
        val cachedAccount = account()
        val repository = FakeAccountsRepository(listOf(cachedAccount)).apply {
            refreshFailure = IllegalStateException("offline")
        }
        val store = AccountsStore(repository, backgroundScope)
        runCurrent()
        val effect = backgroundScope.async { store.effects.first() }
        runCurrent()

        store.refresh()
        runCurrent()

        assertEquals(listOf(cachedAccount), store.state.value.accounts)
        assertEquals(AccountsFailure.REFRESH_FAILED, store.state.value.failure)
        assertFalse(store.state.value.refreshing)
        assertEquals(
            AccountsEffect.ShowMessage(AccountsFailure.REFRESH_FAILED),
            effect.await(),
        )
    }

    @Test
    fun ownerCancellationStopsRefreshWithoutPublishingFailure() = runTest {
        val repository = FakeAccountsRepository(listOf(account())).apply {
            refreshGate = CompletableDeferred()
        }
        val owner = SupervisorJob()
        val store = AccountsStore(repository, CoroutineScope(coroutineContext + owner))
        runCurrent()

        store.refresh()
        runCurrent()
        assertTrue(store.state.value.refreshing)

        owner.cancel()
        runCurrent()

        assertFalse(store.state.value.refreshing)
        assertEquals(null, store.state.value.failure)
    }

    @Test
    fun cacheObservationFailureHasPersistentUnavailableState() = runTest {
        val repository = object : AccountsRepository {
            override fun observeAccounts(): Flow<List<Account>> = flow {
                throw IllegalStateException("database unavailable")
            }

            override suspend fun refresh() = Unit
        }
        val store = AccountsStore(repository, backgroundScope)

        runCurrent()

        assertEquals(AccountsCacheStatus.UNAVAILABLE, store.state.value.cacheStatus)
        assertEquals(AccountsFailure.CACHE_UNAVAILABLE, store.state.value.failure)
    }
}

private class FakeAccountsRepository(initial: List<Account>) : AccountsRepository {
    private val values = MutableStateFlow(initial)
    var refreshes = 0
    var refreshGate: CompletableDeferred<Unit>? = null
    var refreshFailure: Exception? = null

    override fun observeAccounts(): Flow<List<Account>> = values

    override suspend fun refresh() {
        refreshes += 1
        refreshFailure?.let { throw it }
        refreshGate?.await()
    }
}

private fun account(
    id: String = "account-everyday",
    updatedAt: Long = 1,
): Account = Account(
    id = id,
    displayName = "Everyday",
    maskedNumber = "•••• 0001",
    balance = Money(500, CurrencyCode("EUR")),
    updatedAtEpochMillis = updatedAt,
)
