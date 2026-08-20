# Mobile Lifecycle and Recovery Contract

This document separates lifecycle notifications from business guarantees. A lifecycle callback can trigger reconciliation, but it is not durable ownership of a financial operation.

## State categories

### Durable business state

Persist data needed to determine what happened after the current process disappears:

- stable operation identifier;
- immutable transfer fingerprint or all fields required to verify it;
- creation/submission timestamps where they are operational metadata, not trusted business time;
- last known backend identifier and status;
- whether the outcome is pending or unknown;
- reconciliation attempt metadata that is safe to persist.

The application uses SQLDelight for durable shared records. Candidate changes may refine which transfer fields and transitions belong there.

### Transient UI state

Text-field focus, expanded menus, current animation, and progress rendering can be recreated. Android and iOS provide different restoration mechanisms and lifetimes for presentation state; document any guarantee your change relies on.

### One-time effects

Navigation, toasts, and permission prompts have different delivery requirements from rendered screen state. Their delivery mechanism must define what a new collector observes after recreation.

## Android behavior

- Configuration change normally retains an AndroidX `ViewModel`, but destroys and recreates the Activity and Compose tree.
- The process can be killed while the task is backgrounded; in-memory scopes and `ViewModel` instances are then lost.
- `SavedStateHandle` and saved instance state are size-limited restoration aids. They are not a transaction log and may not survive force-stop/data clearing.
- Coroutine lifetime follows its owning scope. Platform teardown can happen before an in-flight network response reaches presentation code.
- WorkManager provides deferrable scheduled execution, not an exactly-once financial guarantee. Foreground and worker reconciliation can overlap and therefore require shared persistent coordination.
- Force-stop prevents scheduled work until the user explicitly launches the app again.

The Android application forwards process foreground/background state into the shared lifecycle contract. Shared recovery code must remain correct if a callback is delayed, duplicated, or never delivered because the process died.

## iOS behavior

- Moving to the background can lead to suspension with little notice. Suspension pauses execution; it does not imply cancellation or process death.
- The system can later terminate a suspended process without running application cleanup code.
- On resume, a coroutine may continue with UI state that is no longer current; on termination, all in-memory scopes are lost.
- General-purpose background execution time is limited and not guaranteed. Background tasks are scheduling opportunities, not durable ownership or exactly-once delivery.
- SwiftUI scene phase and UIKit application callbacks are signals. The shared persistent state remains the recovery source.
- Force-quit has platform-specific effects on background delivery and push handling; recovery must work at a later explicit launch.

The iOS shell forwards application/scene transitions to the shared lifecycle observer and hosts the Compose framework. Swift and Kotlin coroutine cancellation must not be assumed to have identical timing.

## Common recovery behavior

On startup or a suitable foreground signal, the application can compare locally known operations with the authoritative backend status. The exact polling, coordination, persistence, and presentation policy is part of the design under review; it must account for different Android and iOS lifecycle sequences.

## Cancellation rules

- Re-throw or propagate coroutine cancellation; do not broadly map it to a business rejection.
- Use cleanup only for local resources that can actually be cleaned up.
- Document which local records are retained or removed for each transport and backend response category.
- Distinguish an explicit server response from a connection ending before a response is received.

## Verification expectations

At least one selected-slice test should model a lifecycle or transport boundary deterministically, without arbitrary sleeps. Record Android and iOS checks separately.
A common test can prove shared state-machine behavior, but it cannot prove Keychain access policy, Android process restoration, iOS suspension timing, or platform background scheduling.
