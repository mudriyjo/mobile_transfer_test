package com.bank.mobile.feature.beneficiaries

import com.bank.mobile.core.network.BeneficiaryAlreadyExistsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class BeneficiaryCacheStatus {
    LOADING,
    AVAILABLE,
    EMPTY,
    UNAVAILABLE,
}

enum class BeneficiaryFailure {
    CACHE_UNAVAILABLE,
    REFRESH_FAILED,
    ALREADY_EXISTS,
    SAVE_FAILED,
}

data class BeneficiaryState(
    val beneficiaries: List<Beneficiary> = emptyList(),
    val cacheStatus: BeneficiaryCacheStatus = BeneficiaryCacheStatus.LOADING,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val failure: BeneficiaryFailure? = null,
    val validationErrors: BeneficiaryValidationErrors = BeneficiaryValidationErrors(),
    val lastSavedBeneficiaryId: String? = null,
) {
    val isInitialLoading: Boolean
        get() = cacheStatus == BeneficiaryCacheStatus.LOADING && beneficiaries.isEmpty()

    val canSubmit: Boolean
        get() = !refreshing && !saving
}

/**
 * Owns beneficiary presentation state while the SQLDelight cache remains the data source of truth.
 * The unmasked account identifier only crosses [save] as a transient request value; it is never
 * copied into state or persisted locally.
 */
class BeneficiaryStore(
    private val repository: BeneficiaryRepository,
    private val scope: CoroutineScope,
) {
    private val writeMutex = Mutex()
    private val mutableState = MutableStateFlow(BeneficiaryState())

    val state: StateFlow<BeneficiaryState> = mutableState.asStateFlow()

    init {
        scope.launch {
            repository.observe()
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    if (failure !is Exception) throw failure
                    mutableState.update { current ->
                        current.copy(
                            cacheStatus = BeneficiaryCacheStatus.UNAVAILABLE,
                            failure = BeneficiaryFailure.CACHE_UNAVAILABLE,
                        )
                    }
                }
                .collect { beneficiaries ->
                    mutableState.update { current ->
                        current.copy(
                            beneficiaries = beneficiaries,
                            cacheStatus = if (beneficiaries.isEmpty()) {
                                BeneficiaryCacheStatus.EMPTY
                            } else {
                                BeneficiaryCacheStatus.AVAILABLE
                            },
                            failure = current.failure.takeUnless {
                                it == BeneficiaryFailure.CACHE_UNAVAILABLE
                            },
                        )
                    }
                }
        }
    }

    fun refresh() {
        if (!writeMutex.tryLock()) return
        mutableState.update { current ->
            current.copy(
                refreshing = true,
                failure = current.failure.takeIf {
                    it == BeneficiaryFailure.CACHE_UNAVAILABLE
                },
            )
        }
        scope.launch {
            try {
                repository.refresh()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableState.update { current ->
                    current.copy(failure = BeneficiaryFailure.REFRESH_FAILED)
                }
            }
        }.invokeOnCompletion {
            mutableState.update { it.copy(refreshing = false) }
            writeMutex.unlock()
        }
    }

    fun save(draft: BeneficiaryDraft) {
        val validation = draft.validate()
        if (!validation.isValid) {
            mutableState.update { current ->
                current.copy(
                    validationErrors = validation,
                    failure = null,
                    lastSavedBeneficiaryId = null,
                )
            }
            return
        }
        if (!writeMutex.tryLock()) return

        mutableState.update { current ->
            current.copy(
                saving = true,
                failure = null,
                validationErrors = BeneficiaryValidationErrors(),
                lastSavedBeneficiaryId = null,
            )
        }
        scope.launch {
            try {
                val saved = repository.create(draft.normalized())
                mutableState.update { current ->
                    current.copy(lastSavedBeneficiaryId = saved.id)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: BeneficiaryAlreadyExistsException) {
                mutableState.update { current ->
                    current.copy(failure = BeneficiaryFailure.ALREADY_EXISTS)
                }
            } catch (_: Exception) {
                mutableState.update { current ->
                    current.copy(failure = BeneficiaryFailure.SAVE_FAILED)
                }
            }
        }.invokeOnCompletion {
            mutableState.update { it.copy(saving = false) }
            writeMutex.unlock()
        }
    }

    fun clearFormFeedback() {
        mutableState.update { current ->
            current.copy(
                failure = current.failure.takeIf {
                    it == BeneficiaryFailure.CACHE_UNAVAILABLE ||
                        it == BeneficiaryFailure.REFRESH_FAILED
                },
                validationErrors = BeneficiaryValidationErrors(),
                lastSavedBeneficiaryId = null,
            )
        }
    }
}
