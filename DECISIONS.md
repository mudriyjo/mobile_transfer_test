# Problem

**Concrete failure sequence:**

1. User confirms transfer → biometric authentication succeeds
2. ViewModel generates fresh operation ID (TransferViewModel.kt:115)
3. Repository calls remote API with operation ID as idempotency key
4. Backend commits transfer successfully
5. Network timeout/connection loss/app termination occurs before response received
6. Repository catches exception and deletes local operation record (TransferRepositoryImpl.kt:38)
7. User sees "Transfer failed" with retry option
8. User retries → ViewModel generates NEW operation ID
9. Backend receives different idempotency key → executes SECOND transfer
10. Customer charged twice

**Root cause:** Operation ID is generated fresh on each confirm attempt and was not persisted before the API call in the original implementation. When an ambiguous network outcome occurs after server commit, the operation ID could be lost and retry would create a duplicate transfer with a different idempotency key.

# User and Production Impact

**Customer impact:**
- Duplicate financial execution: charged twice for the same intended transfer
- Incorrect status display: sees "failed" when transfer actually succeeded
- Loss of trust in mobile banking reliability
- Potential overdraft or insufficient funds on retry

**Bank impact:**
- Potential compliance/regulatory exposure: duplicate execution may violate payment accuracy requirements
- Financial liability: must refund duplicate charges and compensate customers
- Operational burden: manual reconciliation, customer support escalations
- Reputational damage: loss of customer confidence in digital channels

**Support/operations impact:**
- Increased support tickets for "duplicate transfer" complaints
- Manual investigation required to distinguish legitimate duplicates from idempotency violations
- Reconciliation complexity when operation IDs don't match backend transfer IDs

# Chosen Scope

**Vertical slice implemented:** Stable operation identity with durable intent persistence.

**Invariant:** The operation ID for a transfer intent must be durably persisted before the first API call and reused for any retry of the same customer intent. The durable record is intended to support recovery after process death; automatic ViewModel restoration of `pendingOperationId` is outside this change.

**Specific changes:**
1. Persist transfer intent (operation ID + payload) to SQLDelight BEFORE calling remote API
2. Preserve ambiguous-outcome operations locally instead of deleting them
3. Reuse existing pending operation ID when user retries the same draft
4. Distinguish ambiguous outcomes from definitive rejections in local state

**What IS included:**
- Durable operation ID persistence before remote submission
- Operation ID reuse across retry attempts
- Ambiguous outcome preservation for reconciliation
- Shared-layer repository and local data source changes
- Focused unit tests for the invariant

**What is NOT included:**
- ViewModel state restoration across process death (separate lifecycle issue)
- Navigation event replay prevention (separate UI issue)
- Offline queue or background retry scheduler
- User-facing conflict resolution UI
- Analytics/PII sanitization
- Platform-specific secure storage improvements
- Operation ID format changes
- Scheduled payment redesign (compatibility maintained)

# Decisions

**Key decisions and safety rationale:**

1. **Persist intent before remote call**
   - Safe: the SQLDelight write is performed before the remote call, so the operation identity is available locally before submission
   - Safe under timeout: the persisted operation ID can be reused after an ambiguous client timeout
   - Safe under cancellation: if cancellation occurs after persistence, the intent remains available for reconciliation
   - Process-death behavior: the durable record is intended to survive app restart, but physical process-termination durability was not device-tested

2. **Preserve ambiguous outcomes as OUTCOME_UNKNOWN**
   - Safe: distinguishes "outcome unknown" from "definitely failed"
   - Safe under repeated input: same operation ID prevents duplicate submission via idempotency
   - Safe under restoration: reconciliation can query backend status by operation ID

3. **Reuse pending operation ID for retry**
   - Safe: idempotency contract guarantees same key + same payload returns existing transfer
   - Safe under repeated input: prevents duplicate execution even if user taps retry multiple times
   - Implementation: ViewModel stores `pendingOperationId` in current instance; full process-death recovery not device-tested

4. **Delete local record for specific failure categories**
   - Deletes for: `NoInternetException`, `DefinitiveRejectionException`, `IdempotencyConflictException`, `AuthenticationException`
   - Preserves as `OUTCOME_UNKNOWN` for: other exceptions including timeouts, connection loss, malformed responses
   - Safe: terminal rejections (422) cannot succeed on retry; connectivity errors may be misclassified (residual risk)
   - Safe: preserves ambiguous outcomes for status reconciliation

# Shared vs Platform-specific Responsibility

**Shared code ownership (implemented):**
- Transfer state machine and status transitions (TransferStateReducer)
- Operation ID generation and reuse logic (TransferViewModel)
- Durable intent persistence (TransferLocalDataSource via SQLDelight)
- Remote API coordination and idempotency (TransferRepositoryImpl, TransferRemoteDataSource)
- Ambiguous outcome detection and preservation (TransferRepositoryImpl)
- Status reconciliation after foreground (TransferViewModel)

**Platform-specific responsibilities (unchanged):**
- SQLDelight database driver (Android: AndroidSqliteDriver, iOS: NativeSqliteDriver)
- Lifecycle observation triggers (Android: ProcessLifecycleOwner, iOS: UIApplication notifications)
- Biometric authentication (Android: BiometricPrompt, iOS: LocalAuthentication)
- Secure credential storage (Android: EncryptedSharedPreferences, iOS: Keychain)

**Platform assumptions (from code/documentation review):**
- SQLDelight transaction isolation should be equivalent on both Android and iOS drivers
- Lifecycle foreground events trigger reconciliation on both platforms
- Database writes should survive process termination on both platforms
- No platform-specific production code changes were required for this invariant

**Unverified platform behavior:**
- SQLDelight transaction durability guarantees (WAL mode, synchronous writes) not experimentally verified
- Exact timing of Android background process kill vs. SQLDelight flush
- iOS background task completion guarantees for database writes
- Force-quit behavior on physical devices (not tested on device or simulator/emulator)
- Full ViewModel state restoration across process death (pendingOperationId is in-memory only)

# Alternatives

**1. ViewModel-only operation ID preservation (rejected)**
- Store operation ID in ViewModel state without durable persistence
- **Why rejected:** ViewModel does not survive process death; operation ID would be lost on background kill, force quit, or low-memory termination
- **Evidence:** docs/current-architecture.md states "UI-local values remain transient unless required to reconstruct submitted customer intent"

**2. Durable persistence without stable ID reuse (rejected)**
- Persist operation after API call but generate new ID on retry
- **Why rejected:** Does not prevent duplicate execution; new operation ID bypasses idempotency contract
- **Evidence:** docs/transfer-api.md idempotency semantics require stable operation ID across retry attempts

**3. Broad lifecycle/navigation/offline architecture refactor (rejected)**
- Redesign ViewModel restoration, navigation effects, offline queue, background retry
- **Why rejected:** Too broad for timebox; does not address the specific duplicate-execution risk; introduces more complexity and risk
- **Evidence:** ASSIGNMENT.md:13 "A focused, proven change is valued more highly than a broad refactor"

**4. Backend-side duplicate detection (rejected)**
- Rely on backend to detect duplicate transfers by payload fingerprint without operation ID
- **Why rejected:** Backend already provides idempotency via operation ID; client must honor the contract; backend cannot distinguish legitimate retry from new intent without stable key
- **Evidence:** docs/transfer-api.md:44 "Idempotency is a client/server protocol. A UI debounce, local mutex, or connectivity check does not replace it."

**5. User-facing conflict resolution UI (deferred)**
- Show user a choice when 409 Conflict occurs
- **Why deferred:** Conflict should not occur in normal operation if operation ID is stable; UI complexity not justified for edge case; can be added later if telemetry shows conflicts

# Validation

**Automated test evidence:**
- Command: `./gradlew :shared:jvmTest --tests TransferRepositoryImplTest --no-build-cache --no-configuration-cache`
- Result: BUILD SUCCESSFUL
- Coverage: 
  1. Intent is persisted before the remote API call
  2. Ambiguous outcome preserves the local operation as `OUTCOME_UNKNOWN`
- **Not covered by focused tests:** ViewModel retry reuse, definitive rejection deletion, reconciliation flow, backend integration scenarios

**Code review evidence:**
- Inspected TransferRepositoryImpl.kt diff: saveIntent() called before remote.create()
- Inspected TransferLocalDataSource.kt: saveIntent() implementation verified
- Inspected TransferViewModel.kt: pending operation ID reuse logic verified
- Reviewed Git history: confirmed focused changes, rejected broader refactor commits

**API/documentation review:**
- docs/transfer-api.md: idempotency semantics, outcome certainty, 409 Conflict behavior
- docs/current-architecture.md: SQLDelight as source of truth, repository coordination
- Reviewed backend stub implementation for deterministic ambiguous-outcome scenarios

**Build/test verification:**
- Focused JVM test compilation succeeded
- Existing Gradle deprecation warnings remain
- Type safety preserved across repository/local/remote boundaries

**Checks NOT performed:**
- Android device or emulator testing (force-quit, background kill, low-memory scenarios)
- iOS device or simulator testing (background termination, app suspension, memory pressure)
- Scheduled payment occurrence submission with new persistence timing
- Full integration test with backend stub ambiguous-outcome scenarios (COMMIT_THEN_TIMEOUT, etc.)
- Performance impact of additional SQLDelight write before API call
- SQLDelight transaction durability under process termination (reviewed in documentation only)
- ViewModel state restoration across process death (pendingOperationId is in-memory)
- Concurrent transfer submission from multiple app instances (not a supported scenario)

# Residual Risks

**1. Platform-specific process death behavior not verified**
- **Condition:** Android/iOS background kill timing vs. SQLDelight flush completion
- **Mitigation:** SQLDelight transaction semantics reviewed in documentation; durability expected but not device-tested
- **Remaining work:** Physical device testing with force-quit, background kill, and low-memory scenarios on both platforms

**2. Scheduled payment compatibility not regression-tested**
- **Condition:** Scheduled payment occurrence submission shares TransferRepositoryImpl
- **Mitigation:** Occurrence ID generation is separate; payload structure unchanged
- **Remaining work:** Regression test scheduled payment submission with new persistence timing

**3. Full offline/background retry not implemented**
- **Condition:** User remains offline after ambiguous outcome; no automatic reconciliation
- **Mitigation:** Foreground reconciliation triggers on app relaunch
- **Remaining work:** Background work scheduler for periodic status checks

**4. User-facing conflict UX not redesigned**
- **Condition:** 409 Conflict returns generic error message
- **Mitigation:** Conflict should be rare if operation ID is stable; telemetry can detect frequency
- **Remaining work:** Dedicated conflict message if telemetry shows material occurrence

**5. Backend idempotency contract is a dependency**
- **Condition:** Backend changes payload fingerprint algorithm without coordination
- **Mitigation:** API contract documents canonicalization; mobile/backend versions must be compatible
- **Remaining work:** Coordinate any future payload changes with backend team

**6. Connectivity error classification**
- **Condition:** `NoInternetException` deletes local record; may misclassify some ambiguous network failures
- **Mitigation:** Most ambiguous outcomes (timeout, connection loss, malformed response) preserved as `OUTCOME_UNKNOWN`
- **Remaining work:** Review connectivity check accuracy; consider preserving `NoInternetException` as ambiguous if advisory check is unreliable

**7. Cancellation semantics during persistence**
- **Condition:** Coroutine cancellation between saveIntent() and remote.create()
- **Mitigation:** Intent remains persisted; reconciliation can recover
- **Remaining work:** Explicit cancellation test for this boundary

# Non-goals

**Intentionally excluded work:**

1. **Architecture rewrite:** No broad refactor of repository, ViewModel, or navigation patterns
2. **Offline queue/background retry:** No automatic retry scheduler or background work manager
3. **Navigation replay fixes:** Navigation event replay after process death remains a separate issue
4. **Analytics/PII changes:** Sensitive data in telemetry not addressed
5. **Secure credential storage:** Platform-specific token storage improvements not included
6. **Operation ID format redesign:** ULID/UUID generation algorithm unchanged
7. **Conflict UX redesign:** No user-facing conflict resolution flow
8. **Scheduled payment redesign:** Occurrence identity and payload semantics unchanged
9. **Network reachability improvements:** Advisory connectivity check unchanged
10. **Biometric authentication restoration:** Authentication state across recreation not addressed

# Release Recommendation

**Decision:** `CONDITIONAL GO`

**What is safe:**
- With the documented backend idempotency contract, operation ID persistence before the API call and reuse across retry prevents duplicate execution for an ambiguous outcome
- Operation ID reuse across retry honors idempotency contract
- Ambiguous outcomes preserved for reconciliation instead of deleted
- Shared-layer change benefits both Android and iOS equally
- No platform-specific production code changes required
- Backward compatible with existing backend idempotency contract
- Focused change with clear invariant and test coverage

**Mandatory pre-release conditions:**

1. **Platform verification:**
   - Test force-quit → relaunch → reconciliation on Android physical device or emulator
   - Test background termination → relaunch → reconciliation on iOS physical device or simulator
   - Verify SQLDelight transaction durability under process termination on both platforms

2. **Scheduled payment regression:**
   - Verify scheduled payment occurrence submission works with new persistence timing
   - Confirm occurrence ID generation and payload structure unchanged

3. **Integration testing:**
   - Run backend stub ambiguous-outcome scenarios: COMMIT_THEN_TIMEOUT, COMMIT_THEN_MALFORMED_RESPONSE, BLOCK_AFTER_COMMIT
   - Verify reconciliation finds and updates ambiguous operations
   - Confirm 409 Conflict handling when operation ID is reused with different payload

**Compatible app/backend versions:**
- Mobile change is compatible with existing backend idempotency contract
- No backend changes required
- Existing and updated mobile clients can coexist (both honor idempotency-key semantics)

**Required Android/iOS checks:**
- Android: Process death during network call, background restrictions, low memory
- iOS: Background termination, app suspension, memory pressure
- Both: Force quit, airplane mode toggle, network timeout scenarios

# Rollout and Rollback

**Rollout:**
- Standard staged mobile release if applicable to deployment process
- Monitor duplicate transfer reports after rollout
- No backend changes required

**Rollback:**
- Revert via standard mobile release process
- No backend coordination required; rollback is client-only
- Already-installed updated clients continue to honor idempotency correctly
- Rolled-back clients revert to previous (riskier) behavior but remain compatible with backend

**Mobile/backend compatibility:**
- Mobile change is compatible with existing backend idempotency contract
- No backend changes required
- Existing and updated mobile clients can coexist (both honor idempotency-key semantics)
