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

**Root cause:** Operation ID is generated fresh on each confirm attempt and not persisted before the API call. When an ambiguous network outcome occurs after server commit, the operation ID is lost and retry creates a duplicate transfer with a different idempotency key.

# User and Production Impact

**Customer impact:**
- Duplicate financial execution: charged twice for the same intended transfer
- Incorrect status display: sees "failed" when transfer actually succeeded
- Loss of trust in mobile banking reliability
- Potential overdraft or insufficient funds on retry

**Bank impact:**
- Regulatory breach: duplicate execution violates payment accuracy requirements
- Financial liability: must refund duplicate charges and compensate customers
- Operational burden: manual reconciliation, customer support escalations
- Reputational damage: loss of customer confidence in digital channels

**Support/operations impact:**
- Increased support tickets for "duplicate transfer" complaints
- Manual investigation required to distinguish legitimate duplicates from idempotency violations
- Reconciliation complexity when operation IDs don't match backend transfer IDs

# Chosen Scope

**Vertical slice implemented:** Stable operation identity with durable intent persistence.

**Invariant:** The operation ID for a transfer intent must be durably persisted before the first API call, survive process death, and be reused for any retry or recovery of the same customer intent.

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
   - Safe: SQLDelight transaction ensures atomicity; if remote call fails, intent remains for retry
   - Safe under timeout: operation ID survives client timeout and can be reused
   - Safe under cancellation: coroutine cancellation after persistence preserves operation for reconciliation
   - Safe under process death: SQLDelight persists to disk; operation survives app restart

2. **Preserve ambiguous outcomes instead of deleting**
   - Safe: distinguishes "outcome unknown" from "definitely failed"
   - Safe under repeated input: same operation ID prevents duplicate submission via idempotency
   - Safe under restoration: reconciliation can query backend status by operation ID

3. **Reuse pending operation ID for retry**
   - Safe: idempotency contract guarantees same key + same payload returns existing transfer
   - Safe under repeated input: prevents duplicate execution even if user taps retry multiple times
   - Safe under restoration: ViewModel checks for pending operation before generating new ID

4. **Delete only on definitive rejection**
   - Safe: 422 business rejection is terminal and cannot succeed on retry
   - Safe: preserves ambiguous outcomes for status reconciliation
   - Safe: prevents retry loop on permanent failures

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

**Platform assumptions:**
- SQLDelight transaction isolation is equivalent on both Android and iOS drivers
- Lifecycle foreground events trigger reconciliation on both platforms
- Database writes survive process termination on both platforms
- No platform-specific production code changes were required for this invariant

**Unverified platform behavior:**
- Exact timing of Android background process kill vs. SQLDelight flush
- iOS background task completion guarantees for database writes
- Force-quit behavior on physical devices (not tested in simulator/emulator)

# Alternatives

**1. ViewModel-only operation ID preservation (rejected)**
- Store operation ID in ViewModel state without durable persistence
- **Why rejected:** ViewModel does not survive process death; operation ID would be lost on background kill, force quit, or low-memory termination
- **Evidence:** docs/current-architecture.md states "UI-local values remain transient unless required to reconstruct submitted customer intent"

**2. Durable persistence without stable ID reuse (rejected)**
- Persist operation after API call but generate new ID on retry
- **Why rejected:** Does not prevent duplicate execution; new operation ID bypasses idempotency contract
- **Evidence:** docs/transfer-api.md:47 "Repeating the same identifier with a different canonical payload returns 409 Conflict"

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
- Command: `./gradlew :shared:jvmTest --tests TransferRepositoryImplTest`
- Result: PASSED
- Coverage: Operation ID persistence before API call, operation ID reuse on retry, ambiguous outcome preservation, definitive rejection deletion

**Code review evidence:**
- Inspected TransferRepositoryImpl.kt diff: saveIntent() called before remote.create()
- Inspected TransferLocalDataSource.kt: saveIntent() implementation verified
- Inspected TransferViewModel.kt: pending operation ID reuse logic verified
- Reviewed Git history: confirmed focused changes, rejected broader refactor commits

**API/documentation review:**
- docs/transfer-api.md: idempotency semantics, outcome certainty, 409 Conflict behavior
- docs/current-architecture.md: SQLDelight as source of truth, repository coordination
- Confirmed backend stub implements deterministic ambiguous-outcome scenarios

**Static analysis:**
- Kotlin compilation successful
- No new compiler warnings introduced
- Type safety preserved across repository/local/remote boundaries

**Checks NOT performed:**
- Android physical device force-quit → relaunch → reconciliation
- iOS physical device background termination → relaunch → reconciliation
- Scheduled payment occurrence submission with new persistence timing
- Full integration test with backend stub ambiguous-outcome scenarios
- Performance impact of additional SQLDelight write before API call
- Concurrent transfer submission from multiple app instances (not a supported scenario)

# Residual Risks

**1. Platform-specific process death behavior not fully verified**
- **Condition:** Android/iOS background kill timing vs. SQLDelight flush completion
- **Mitigation:** SQLDelight uses synchronous writes; transaction commit should be durable
- **Remaining work:** Physical device testing with force-quit and low-memory scenarios

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

**6. Cancellation semantics during persistence**
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
- Operation ID persistence before API call prevents duplicate execution after ambiguous outcome
- Operation ID reuse across retry honors idempotency contract
- Ambiguous outcomes preserved for reconciliation instead of deleted
- Shared-layer change benefits both Android and iOS equally
- No platform-specific production code changes required
- Backward compatible with existing backend idempotency contract
- Focused change with clear invariant and test coverage

**Mandatory pre-release conditions:**

1. **Platform verification:**
   - Test force-quit → relaunch → reconciliation on Android physical device
   - Test background termination → relaunch → reconciliation on iOS physical device
   - Verify SQLDelight transaction durability on both platforms under low-memory conditions

2. **Scheduled payment regression:**
   - Verify scheduled payment occurrence submission works with new persistence timing
   - Confirm occurrence ID generation and payload structure unchanged
   - Test scheduled payment status reconciliation

3. **Integration testing:**
   - Run full backend stub scenarios: COMMIT_THEN_TIMEOUT, COMMIT_THEN_MALFORMED_RESPONSE, BLOCK_AFTER_COMMIT
   - Verify reconciliation finds and updates ambiguous operations
   - Confirm 409 Conflict handling when operation ID is reused with different payload

4. **Telemetry:**
   - Add metric for operation ID reuse vs. new generation
   - Add metric for 409 Conflict frequency
   - Add metric for reconciliation success rate
   - Add alert for operations stuck in ambiguous state beyond threshold (e.g., 24 hours)

**Compatible app/backend versions:**
- Mobile change is compatible with existing backend idempotency contract
- No backend changes required
- Existing and updated mobile clients can coexist (both honor idempotency-key semantics)

**Required Android/iOS checks:**
- Android: Process death during network call, background restrictions, low memory
- iOS: Background termination, app suspension, memory pressure
- Both: Force quit, airplane mode toggle, network timeout scenarios

**Post-release metrics:**
- Operation ID collision rate (should be zero with ULID/UUID)
- 409 Conflict frequency (should be rare; indicates payload canonicalization issue if frequent)
- Duplicate transfer reports (should decrease significantly)
- Reconciliation success rate (should be high for ambiguous outcomes)
- Operations in ambiguous state beyond 24 hours (should be rare; indicates reconciliation gap)

# Rollout and Rollback

**Rollout strategy:**
- Phased rollout: 5% → 25% → 50% → 100% over 2 weeks
- Monitor duplicate transfer reports and 409 Conflict metrics at each phase
- Pause rollout if duplicate rate does not decrease or conflict rate increases

**Feature flag (optional):**
- Not required for this change; behavior is always safer than previous version
- If desired: flag to control whether ambiguous outcomes are preserved vs. deleted (default: preserve)

**Rollback:**
- Revert production change via standard mobile release process
- No backend changes required; rollback is client-only
- Already-installed updated clients will continue to honor idempotency correctly
- Rolled-back clients will revert to previous (riskier) behavior but remain compatible with backend

**Emergency mitigation:**
- Backend can implement server-side duplicate detection by payload fingerprint as temporary safeguard
- Backend can reject suspicious duplicate operation IDs (same source/destination/amount within short time window)
- Customer support can manually reconcile duplicate transfers while rollback deploys

**Mobile version compatibility:**
- Updated mobile version is backward compatible with backend
- Previous mobile version remains forward compatible with backend
- No coordinated mobile/backend deployment required
- Gradual user upgrade is safe
