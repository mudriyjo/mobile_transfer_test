package com.bank.mobile.feature.accounts

import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class AccountsCacheStatus {
    LOADING,
    AVAILABLE,
    EMPTY,
    UNAVAILABLE,
}

enum class AccountsFailure {
    CACHE_UNAVAILABLE,
    REFRESH_FAILED,
}

data class AccountsState(
    val accounts: List<Account> = emptyList(),
    val cacheStatus: AccountsCacheStatus = AccountsCacheStatus.LOADING,
    val refreshing: Boolean = false,
    val failure: AccountsFailure? = null,
    val staleAccountIds: Set<String> = emptySet(),
    val oldestUpdateEpochMillis: Long? = null,
) {
    val isInitialLoading: Boolean
        get() = cacheStatus == AccountsCacheStatus.LOADING && accounts.isEmpty()

    fun isStale(accountId: String): Boolean = accountId in staleAccountIds
}

sealed interface AccountsEffect {
    data class ShowMessage(val failure: AccountsFailure) : AccountsEffect
}

class AccountsStore(
    private val repository: AccountsRepository,
    private val scope: CoroutineScope,
    private val clock: EpochClock = DeviceEpochClock,
    private val cacheMaxAgeMillis: Long = DEFAULT_CACHE_MAX_AGE_MILLIS,
) {
    private val refreshMutex = Mutex()
    private val stateMutable = MutableStateFlow(AccountsState())
    private val effectsMutable = MutableSharedFlow<AccountsEffect>(extraBufferCapacity = 1)

    val state: StateFlow<AccountsState> = stateMutable.asStateFlow()
    val effects: SharedFlow<AccountsEffect> = effectsMutable.asSharedFlow()

    init {
        require(cacheMaxAgeMillis >= 0) { "cacheMaxAgeMillis must not be negative" }
        scope.launch {
            repository.observeAccounts()
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    if (failure !is Exception) throw failure
                    stateMutable.update { current ->
                        current.copy(
                            cacheStatus = AccountsCacheStatus.UNAVAILABLE,
                            failure = AccountsFailure.CACHE_UNAVAILABLE,
                        )
                    }
                }
                .collect { accounts ->
                    val nowMillis = clock.nowMillis()
                    stateMutable.update { current ->
                        current.copy(
                            accounts = accounts,
                            cacheStatus = if (accounts.isEmpty()) {
                                AccountsCacheStatus.EMPTY
                            } else {
                                AccountsCacheStatus.AVAILABLE
                            },
                            failure = current.failure.takeIf { it == AccountsFailure.REFRESH_FAILED },
                            staleAccountIds = accounts
                                .filter { it.isOlderThan(nowMillis, cacheMaxAgeMillis) }
                                .mapTo(mutableSetOf(), Account::id),
                            oldestUpdateEpochMillis = accounts.minOfOrNull(Account::updatedAtEpochMillis),
                        )
                    }
                }
        }
    }

    fun refresh() {
        if (!refreshMutex.tryLock()) return

        stateMutable.update { current ->
            current.copy(
                refreshing = true,
                failure = AccountsFailure.CACHE_UNAVAILABLE
                    .takeIf { current.cacheStatus == AccountsCacheStatus.UNAVAILABLE },
            )
        }
        scope.launch {
            try {
                repository.refresh()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                stateMutable.update { it.copy(failure = AccountsFailure.REFRESH_FAILED) }
                effectsMutable.emit(AccountsEffect.ShowMessage(AccountsFailure.REFRESH_FAILED))
            }
        }.invokeOnCompletion {
            val nowMillis = clock.nowMillis()
            stateMutable.update { current ->
                current.copy(
                    refreshing = false,
                    staleAccountIds = current.accounts
                        .filter { it.isOlderThan(nowMillis, cacheMaxAgeMillis) }
                        .mapTo(mutableSetOf(), Account::id),
                )
            }
            refreshMutex.unlock()
        }
    }

    private fun Account.isOlderThan(nowMillis: Long, maxAgeMillis: Long): Boolean {
        if (updatedAtEpochMillis <= 0) return true
        val ageMillis = nowMillis - updatedAtEpochMillis
        return ageMillis >= 0 && ageMillis >= maxAgeMillis
    }

    private companion object {
        const val DEFAULT_CACHE_MAX_AGE_MILLIS = 5 * 60 * 1_000L
    }
}
