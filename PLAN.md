# Critical User Flow

User confirms a transfer → biometric authentication → operation ID generated → API call with idempotency key → local persistence → status observation → result navigation.

**Execution path:**

1. `TransferViewModel.confirm()` validates draft (TransferViewModel.kt:95)
2. Biometric authentication requested (TransferViewModel.kt:109)
3. **Operation ID generated fresh on each confirm** (TransferViewModel.kt:117: `val operationId = operationIds.next()`)
4. Analytics tracked with transfer details (TransferViewModel.kt:119)
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
     - TransferViewModel.kt:117 generates fresh ID on every confirm, not persisted before API call
     - TransferRepositoryImpl.kt:38 deletes operation on any exception including timeout
     - docs/transfer-api.md:95 "Cancellation, timeout... can happen after server commit"
     - TransferViewModel.kt:95 retry action uses same draft but will generate new ID
     - No saveIntent() call before remote.create() in TransferRepositoryImpl.kt:27

2. **Risk: Lost operation status after process death**
   - **Scenario:** Transfer submitted → response received → app killed before navigation completes → user reopens app → no record of transfer → user uncertain if money moved
   - **Impact:** Customer anxiety, support calls, potential duplicate if user resubmits, reconciliation required
   - **Conditions:** Android/iOS background kill during navigation, low memory, force quit
   - **Evidence:**
     - TransferViewModel.kt:130 emits navigation event after persistence but before guaranteed delivery
     - No evidence of ViewModel surviving process death (not SavedStateHandle-backed)
     - TransferViewModel.kt:68 reconcileAfterForeground() only reconciles unfinished transfers
     - TransferLocalDataSource.kt:82 unfinished() queries non-terminal status, but COMPLETED won't reconcile

3. **Risk: Idempotency conflict prevents legitimate retry**
   - **Scenario:** User confirms transfer → 409 Conflict from backend (payload mismatch) → app shows generic failure → user cannot recover without support intervention
   - **Impact:** Customer blocked from completing intended transfer, poor UX, support escalation
   - **Conditions:** Rare but possible if payload canonicalization differs between attempts or backend fingerprint logic changes
   - **Evidence:**
     - TransferViewModel.kt:186 maps IdempotencyConflictException to OPERATION_CONFLICT
     - TransferViewModel.kt:187 sets canTryAgain=false correctly
     - But no user-facing guidance on what "conflict" means or how to resolve
     - docs/transfer-api.md:48 "never generate a replacement key automatically" - correct but no recovery path shown

# Chosen Slice

**Invariant:** The operation ID for a transfer intent must be durably persisted before the first API call, survive process death, and be reused for any protocol-level retry of the same customer intent.

**Why highest value:** Directly prevents duplicate execution (Risk #1), the most severe financial and regulatory impact. Establishes the foundation for proper idempotency. Small, focused change in repository layer.

**Expected files affected:**
- Production: `TransferRepositoryImpl.kt` (add saveIntent before remote call), `TransferLocalDataSource.kt` (verify saveIntent exists)
- Tests: New test in `TransferRepositoryImplTest.kt` or similar for intent persistence, failure recovery test

# Validation Plan

1. **Main invariant check:** Test that operation ID is persisted to local storage before `remote.create()` is invoked, and that the same ID is reused if the repository method is called again with the same draft after a network failure.
   - **Asserts:** SQLDelight contains operation record before API call, same operation ID returned on retry

2. **Ambiguous outcome recovery:** Test that when `remote.create()` throws an exception simulating timeout after commit, the operation record remains in local storage with status OUTCOME_UNKNOWN, and a subsequent retry attempt uses the same operation ID.
   - **Asserts:** No duplicate operation IDs created, local record survives exception, backend journal shows single create attempt

3. **Idempotency conflict detection:** Test that when backend returns 409 Conflict, the local record is marked appropriately and no automatic retry with new ID occurs.
   - **Asserts:** Exception propagated, no new operation ID generated, user informed

4. **Process death simulation:** Test that after saveIntent() but before saveResponse(), the operation can be found and reconciled on next app launch.
   - **Asserts:** Operation survives repository scope cancellation, reconciliation finds pending operation

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

**Safe to release:**
- Operation ID persistence before API call prevents duplicate execution
- Idempotency contract properly honored
- Shared code benefits both platforms equally

**Mandatory before release:**
- Verify SQLDelight transaction durability on both Android and iOS physical devices
- Confirm scheduled payment occurrence IDs work with new persistence timing
- Add telemetry for operation ID reuse vs. new generation to monitor effectiveness
- Test force-quit → relaunch → reconciliation on both platforms

**Post-release monitoring:**
- Track operation ID collision rate (should be zero with ULID/UUID)
- Monitor 409 Conflict frequency to detect payload canonicalization issues
- Measure reconciliation success rate after app foregrounding
- Alert on operations stuck in OUTCOME_UNKNOWN beyond threshold

**Rollback:** If duplicate transfers occur, backend can reject duplicate operation IDs server-side as emergency mitigation while mobile rollback deploys.
