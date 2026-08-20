package com.bank.mobile.feature.beneficiaries

import com.bank.mobile.core.network.BeneficiaryAlreadyExistsException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiaryStoreTest {
    @Test
    fun emptyCacheIsDistinctFromInitialLoading() = runTest {
        val store = BeneficiaryStore(FakeBeneficiaryRepository(), backgroundScope)

        assertTrue(store.state.value.isInitialLoading)
        runCurrent()

        assertEquals(BeneficiaryCacheStatus.EMPTY, store.state.value.cacheStatus)
        assertFalse(store.state.value.isInitialLoading)
    }

    @Test
    fun invalidDraftDoesNotCrossTheRepositoryBoundary() = runTest {
        val repository = FakeBeneficiaryRepository()
        val store = BeneficiaryStore(repository, backgroundScope)
        runCurrent()

        store.save(BeneficiaryDraft("!", "short", "EURO"))
        runCurrent()

        assertEquals(0, repository.createCalls)
        assertFalse(store.state.value.validationErrors.isValid)
        assertFalse(store.state.value.saving)
    }

    @Test
    fun successfulSavePublishesOnlyTheRepositoryMaskedRecord() = runTest {
        val repository = FakeBeneficiaryRepository()
        val store = BeneficiaryStore(repository, backgroundScope)
        runCurrent()

        store.save(
            BeneficiaryDraft(
                "  Taylor   Quinn ",
                "gb82 west 1234 5698 7654 32",
                "eur",
            ),
        )
        runCurrent()

        assertEquals("Taylor Quinn", repository.lastDraft?.displayName)
        assertEquals("GB82WEST12345698765432", repository.lastDraft?.accountIdentifier)
        assertEquals("ben-created", store.state.value.lastSavedBeneficiaryId)
        assertEquals("•••• 5432", store.state.value.beneficiaries.single().maskedAccount)
        assertFalse(store.state.value.toString().contains("GB82WEST"))
    }

    @Test
    fun duplicateConflictIsAStableUserFacingFailure() = runTest {
        val repository = FakeBeneficiaryRepository().apply {
            createFailure = BeneficiaryAlreadyExistsException()
        }
        val store = BeneficiaryStore(repository, backgroundScope)
        runCurrent()

        store.save(validDraft())
        runCurrent()

        assertEquals(BeneficiaryFailure.ALREADY_EXISTS, store.state.value.failure)
        assertNull(store.state.value.lastSavedBeneficiaryId)
        assertFalse(store.state.value.saving)
    }

    @Test
    fun refreshAndSaveUseASingleWriterBoundary() = runTest {
        val repository = FakeBeneficiaryRepository().apply {
            refreshGate = CompletableDeferred()
        }
        val store = BeneficiaryStore(repository, backgroundScope)
        runCurrent()

        store.refresh()
        runCurrent()
        store.save(validDraft())
        runCurrent()

        assertTrue(store.state.value.refreshing)
        assertEquals(0, repository.createCalls)
        repository.refreshGate?.complete(Unit)
        runCurrent()
        assertFalse(store.state.value.refreshing)
    }
}

private class FakeBeneficiaryRepository : BeneficiaryRepository {
    private val values = MutableStateFlow<List<Beneficiary>>(emptyList())
    var createCalls = 0
    var lastDraft: BeneficiaryDraft? = null
    var createFailure: Exception? = null
    var refreshGate: CompletableDeferred<Unit>? = null

    override fun observe(): Flow<List<Beneficiary>> = values

    override suspend fun refresh() {
        refreshGate?.await()
    }

    override suspend fun create(draft: BeneficiaryDraft): Beneficiary {
        createCalls += 1
        lastDraft = draft
        createFailure?.let { throw it }
        return Beneficiary("ben-created", draft.displayName, "•••• 5432", draft.currency)
            .also { values.value = values.value + it }
    }
}

private fun validDraft() = BeneficiaryDraft(
    displayName = "Taylor Quinn",
    accountIdentifier = "GB82WEST12345698765432",
    currency = "EUR",
)
