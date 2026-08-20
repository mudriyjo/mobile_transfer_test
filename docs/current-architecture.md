# Current Architecture

## Module boundaries

```text
Android application                     iOS application
  Activity / intents                      SwiftUI / UIKit host
  Android lifecycle                       iOS lifecycle
  Android storage implementation          iOS storage implementation
  Android biometrics                      LocalAuthentication
           \                               /
            +----- platform contracts ----+
                         |
                    composeApp
               shared UI + navigation
                         |
                      shared
      domain / use cases / reducers / repositories
          SQLDelight source of truth / Ktor client
                         |
                    backendStub
```

`shared` contains portable business and data behavior. `composeApp` owns shared visual composition and feature routing. App modules instantiate platform facilities and supply them to the Koin graph. `backendStub` is an independent JVM/Ktor application; it is not linked into a mobile binary.

## Dependency direction

- Presentation depends on feature contracts/use cases, not platform classes.
- Use cases depend on repository and platform contracts.
- Repository implementations coordinate local and remote data sources.
- SQLDelight is the durable local source of truth.
- Ktor engines, database drivers, lifecycle sources, biometrics, secure storage, and background schedulers are supplied per platform.
- A platform callback may request shared reconciliation, but platform code does not own the transfer state machine.

Koin modules are a useful way to trace concrete implementations. Gradle source sets determine which platform implementation is present in a given binary; class names alone are not proof of release wiring.

## Product flows

### Accounts — reference flow

The accounts feature reads cached records as a `Flow`, refreshes them from the backend when appropriate, stores refresh results transactionally, and retains the last useful cache when refresh fails. Its reducer separates rendered state from one-time effects. Use it as a comparison, not as a requirement to copy every abstraction.

### Instant transfer — assessment target

```text
TransferConfirmationScreen
  -> TransferViewModel / TransferStateReducer
  -> CreateTransferUseCase
  -> TransferRepositoryImpl
  -> TransferLocalDataSource
  -> TransferRemoteDataSource
  -> TransferApi / Ktor client / ApiErrorMapper
  -> local observation and reconciliation
  -> TransferNavigation / result screen
```

Biometric authentication gates submission. Operation identity and backend status are carried across the presentation, repository, persistence, and API layers shown above.

### Scheduled payment — adjacent flow

Scheduled payments persist a queue entry, calculate due work through an injected device-epoch clock, and submit an occurrence through shared transfer infrastructure. Each occurrence has stable identity. Review this flow when changing a shared request model, operation-ID provider, repository, status mapper, database transaction, or background trigger.

### Beneficiaries

Beneficiaries are cached read/write data used to populate a transfer draft. They are intentionally small, but their account-like identifiers remain sensitive for telemetry purposes.

## State and data ownership

- SQLDelight records durable account, transfer-operation, and scheduled-payment state.
- Repository flows are the primary presentation source for durable values.
- Reducers create immutable UI snapshots from domain state and user actions.
- UI-local values remain transient unless they are required to reconstruct a submitted customer intent.
- Backend status is authoritative for a remote financial outcome.
- Connectivity is advisory. It can suppress a wasteful attempt, but cannot prove server availability or operation outcome.

## Navigation

The navigation graph is shared in Compose. Screens collect state and effects. Business status is not encoded only in a navigation back stack. Deep links and push entry points must resolve identifiers through trusted local/backend data and must not authorize or directly submit a transfer from untrusted parameters.

## Expected extension model

New product modules should expose a small feature entry contract, domain/use-case surface, repository contract, and navigation destination rather than requiring edits throughout both app shells. Shared abstractions should represent cross-platform business capabilities. OS-specific guarantees should remain behind narrow contracts instead of imitating a platform API in common code.

## Intentional assessment shape

The repository contains plausible incomplete decisions and production debt across boundaries. Do not infer importance from file size or package position. Build a failure timeline, identify the authoritative state at each boundary, and choose the smallest change that establishes a meaningful invariant.
