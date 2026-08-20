# ADR-001: Shared Compose UI with Explicit Platform Boundaries

- **Status:** Accepted
- **Date:** 2026-08-19

## Context

The product targets Android and iOS with a small mobile organization and multiple feature teams. Accounts, transfer, beneficiary, and scheduled-payment behavior should remain consistent across platforms, while lifecycle, secure storage, biometrics, background execution, deep-link entry, and some operating-system surfaces have materially different guarantees.

Maintaining two unrelated presentation and business implementations would increase behavioral drift in financial state recovery. Moving every operating-system concern into common code would hide platform limitations and make validation misleading.

## Decision

Use Kotlin Multiplatform for domain, application, repositories, persistence coordination, networking contracts, reducers, and most feature UI. Use Compose Multiplatform for shared screens, theme, accessibility semantics, and navigation destinations.

Keep these facilities behind explicit platform contracts with Android and iOS implementations:

- lifecycle observation;
- Ktor engine and SQLDelight driver;
- token/key storage;
- biometric authentication;
- sensitive-screen protection;
- background scheduling hooks;
- push/deep-link application entry;
- device/network signals whose guarantees differ by OS.

The shared layer owns the durable financial state machine and reconciliation rules. Platform code supplies opportunities to run it and must not maintain a competing operation state machine.

## Consequences

### Positive

- Business status and recovery behavior can be tested once in common code.
- Shared UI reduces product drift and provides a common accessibility baseline.
- Platform security and lifecycle APIs remain visible and separately testable.
- Feature teams can add a feature entry contract without editing every platform screen.
- Android and iOS can use different scheduling mechanisms while invoking one persistent reconciliation capability.

### Costs and risks

- Compose and native lifecycle/concurrency interop require explicit tests.
- Some presentation integration remains in Swift and Android application code.
- A common test cannot prove platform API behavior.
- Shared navigation can still replay effects if event ownership is poorly modeled.
- Platform teams must review actual implementations and release wiring, not only expect/contract definitions.

## State ownership rules

- Persistent business data lives below UI in the shared repository/database boundary.
- `ViewModel` and Compose saved state hold presentation data only.
- One-time effects are modeled separately from immutable state and have explicit consumption semantics.
- Lifecycle events request reconciliation; they do not determine whether a transfer succeeded.
- Deep links navigate to authenticated, resolved state and cannot construct a trusted transfer intent from URI fields.

## Alternatives considered

### Fully native UI and state on both platforms

This offers maximum platform idiom but duplicates the high-risk recovery state machine and increases drift. It may be appropriate for a future feature with strongly native interaction requirements, not as the default.

### Share all platform behavior

Rejected because Android process/background rules, iOS suspension/background limits, Keychain/Keystore, and biometric fallback are not interchangeable. A lowest-common-denominator abstraction would obscure important guarantees.

### Share domain/data but use native UI

Viable and lower risk than fully separate applications. It was not selected because the current product benefits from common flows and design-system delivery. The platform boundary in this ADR keeps a later per-feature native UI exception possible.

## Review triggers

Revisit this decision if a shared screen cannot meet accessibility/platform convention, Compose framework startup or memory cost misses agreed budgets, native API interop dominates a feature, or ownership makes shared navigation a delivery bottleneck.
