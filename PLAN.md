# Critical User Flow

User confirms a transfer → biometric authentication → operation ID generated → API call with idempotency key → local persistence → status observation → result navigation.

**Execution path:**

1. `TransferViewModel.confirm()` validates draft (TransferViewModel.kt:95)
2. Biometric authentication requested (TransferViewModel.kt:109)
3. **Operation ID generated fresh on each confirm** (TransferViewModel.kt:115: `val operationId = operationIds.next()`)
4. Analytics tracked with transfer details (TransferViewModel.kt:117)
5. `CreateTransferUseCase` invoked (TransferViewModel.kt:128)
6. `TransferRepositoryImpl.createTransfer()` validates and checks network (TransferRepositoryImpl.kt:18-26)
7. `TransferRemoteDataSource.create()` calls API with operation ID as idempotency key (TransferRemoteDataSource.kt:9)
8. **On success:** `local.saveResponse()` persists with validation (TransferRepositoryImpl.kt:29-35)
9. **On any exception:** `local.delete(operationId)` removes transient record (TransferRepositoryImpl.kt:38)
10. Result emitted, navigation to result screen (TransferViewModel.kt:130)

**Critical gap:** Operation ID is generated in step 3 but not persisted before the API call. If the network call times out after server commit (docs/transfer-api.md:95 "Cancellation, timeout, connection loss... can happen after server commit"), the operation ID is lost when `local.delete()` executes at TransferRepositoryImpl.kt:38.

# Relevant Components

- **Shared:** TransferViewModel, TransferRepositoryImpl, TransferLocalDataSource (SQLDelight), TransferRemoteDataSource (Ktor), OperationIdProvider, TransferStateReducer
- **Persistence:** SQLDelight `Transfer_operation` table, saveIntent/saveResponse/delete operations
- **Backend contract:** docs/transfer-api.md idempotency semantics, `UNKNOWN_TO_CLIENT` outcome
- **Lifecycle:** AppLifecycleObserver (foreground reconciliation), TransferViewModel.reconcileAfterForeground() (TransferViewModel.kt:68)
- **Navigation:** TransferEvent.OpenResult emission (TransferViewModel.kt:130)
- **Scheduled payments:** Share TransferRepositoryImpl, operation ID provider, and status reconciliation

# Top Risks

1. **Risk: Duplicate transfer execution after ambiguous outcome**
   - **Scenario:** User confirms transfer → network timeout after server commit → app shows failure → user retries → new operation ID generated → second transfer executed with different idempotency key
   - **Impact:** Customer charged twice, bank liability, regulatory breach, customer trust loss
   - **Conditions:** Network instability (mobile common), backend accepts first request but client times out, user sees generic failure and retries
   - **Evidence:**
     - TransferViewModel.kt:115 generates fresh ID on every confirm, not persisted before API call
     - TransferRepositoryImpl.kt:38 deletes operation on any exception including timeout
     - docs/transfer-api.md:95 "Cancellation, timeout... can happen after server commit"
     - TransferViewModel.kt:95 retry action uses same draft but will generate new ID
     - No saveIntent() call before remote.create() in TransferRepositoryImpl.kt:27

2. **Risk: Ambiguous outcome presented as definitive failure**
   - **Scenario:** User confirms transfer → network timeout/connection loss after server commit → app shows "Transfer failed" → user believes money did not move → backend has committed the transfer
   - **Impact:** Customer confusion, incorrect mental model of account balance, potential duplicate if user resubmits, reconciliation gap
   - **Conditions:** Network instability, app termination during response, malformed response after commit
   - **Evidence:**
     - TransferRepositoryImpl.kt:38 deletes operation on any exception, losing tracking
     - TransferViewModel.kt:241 maps generic Throwable to "Transfer failed" with canTryAgain=true
     - docs/transfer-api.md:95 "Cancellation, timeout, connection loss... can happen after server commit"
     - No distinction between UnknownOutcomeException and definitive rejection in local persistence
     - User sees same failure UI for network error and business rejection

3. **Risk: Loss of unfinished transfer state after lifecycle interruption**
   - **Scenario:** Transfer submitted → status PROCESSING → app backgrounded → process killed → user reopens app → no pending transfer shown → user resubmits with new operation ID
   - **Impact:** Duplicate transfer execution, lost reconciliation opportunity, customer charged twice
   - **Conditions:** Android/iOS background kill, low memory, force quit, OS resource pressure
   - **Evidence:**
     - TransferViewModel.kt:115 generates fresh operation ID on every confirm, not persisted before call
     - No saveIntent() before remote.create() in TransferRepositoryImpl.kt:27
     - TransferViewModel.kt:68 reconcileAfterForeground() depends on local records existing
     - TransferLocalDataSource.kt:82 unfinished() cannot find operations deleted at line 38
     - ViewModel state does not survive process death (no SavedStateHandle)

# Chosen Slice

**Invariant:** The operation ID for a transfer intent must be durably persisted before the first API call, survive process death, and be reused for any retry or recovery of the same customer intent.

**Why highest value:** Directly prevents duplicate execution (Risk #1 and #3), the most severe financial and regulatory impact. Establishes the foundation for proper idempotency. Requires coordination between ViewModel (operation ID generation/reuse) and repository (intent persistence).

**Expected files affected:**
- Production: `TransferViewModel.kt` (persist and reuse operation ID across retries), `TransferRepositoryImpl.kt` (call saveIntent before remote call), `TransferLocalDataSource.kt` (verify saveIntent exists)
- Tests: New test for operation ID stability across retry, intent persistence before API call, recovery after ambiguous outcome

# Validation Plan

1. **Main invariant check:** Test that operation ID is persisted to local storage before `remote.create()` is invoked, and that the same operation ID is reused when the user retries after a network failure.
   - **Asserts:** SQLDelight contains operation record before API call, ViewModel reuses same operation ID on retry action, no duplicate operation IDs created

2. **Ambiguous outcome recovery:** Test the real path: user confirms transfer (op-1) → network timeout after server commit → app shows ambiguous outcome → user retries → same op-1 is reused → backend returns existing transfer via idempotency.
   - **Asserts:** Backend journal shows single create attempt, local storage contains op-1 throughout, retry uses op-1 not op-2, final state reflects single transfer

3. **Process death simulation:** Test that after saveIntent() but before saveResponse(), the operation can be found and reconciled on next app launch, and that reconciliation does not create a duplicate.
   - **Asserts:** Operation survives ViewModel/repository scope cancellation, reconciliation finds pending operation, no new operation ID generated

4. **Definitive rejection vs ambiguous outcome:** Test that a 422 rejection is distinguished from a timeout, and that only the timeout preserves the operation for retry.
   - **Asserts:** Rejection deletes operation, timeout preserves operation, user sees different failure messages

# Platform Impact

**Shared behavior:** Operation ID persistence, idempotency logic, and retry policy are entirely in shared code (TransferRepositoryImpl). Both platforms benefit equally.

**Android:** SQLDelight uses Android SQLite driver. Process death common due to background restrictions. Lifecycle observation triggers reconciliation via AppLifecycleObserver.

**iOS:** SQLDelight uses native iOS driver. Background termination also common. Same lifecycle observation contract.

**Platform-specific verification needed:** Confirm SQLDelight transaction isolation on both Android and iOS drivers ensures saveIntent() is durable before remote call begins. Verify that a force-quit followed by relaunch triggers reconciliation.

**Scheduled payment impact:** Scheduled payments use the same TransferRepositoryImpl.createTransfer() (docs/current-architecture.md:68). The change ensures scheduled occurrences also persist their operation ID before submission. Must verify that scheduled payment's occurrence ID generation is compatible with the new persistence timing.

# Non-goals

- Fixing all lifecycle issues (e.g., ViewModel recreation, navigation replay)
- Implementing full offline queue or background retry scheduler
- Changing operation ID format or generation algorithm
- Adding user-facing conflict resolution UI beyond error message
- Refactoring entire repository or ViewModel architecture
- Addressing analytics PII concerns (separate issue)
- Implementing platform-specific secure storage improvements
- Fixing indefinite loading states in UI layer

# Residual Risks

- Navigation event replay after process death (separate UI/lifecycle issue)
- Biometric authentication state not surviving recreation
- Network reachability check is advisory, not authoritative for server availability
- Reconciliation depends on foreground trigger; background reconciliation not implemented
- No automatic retry backoff or circuit breaker for repeated failures

# Initial Release Recommendation

**CONDITIONAL GO**

**Safe to release if invariant is implemented and validated:**
- Operation ID persistence before API call prevents duplicate execution
- Operation ID reuse across retry prevents duplicate after ambiguous outcome
- Idempotency contract properly honored
- Shared code benefits both platforms equally

**Mandatory before release:**
- Implement and validate the operation ID persistence and reuse invariant
- Verify SQLDelight transaction durability on both Android and iOS physical devices
- Confirm scheduled payment occurrence IDs work with new persistence timing
- Add telemetry for operation ID reuse vs. new generation to monitor effectiveness
- Test force-quit → relaunch → reconciliation on both platforms
- Verify that ambiguous outcomes are distinguished from definitive failures in UI

**Post-release monitoring:**
- Track operation ID collision rate (should be zero with ULID/UUID)
- Monitor 409 Conflict frequency to detect payload canonicalization issues
- Measure reconciliation success rate after app foregrounding
- Alert on operations stuck in OUTCOME_UNKNOWN beyond threshold

**Rollback:** If duplicate transfers occur, backend can reject duplicate operation IDs server-side as emergency mitigation while mobile rollback deploys.
