# Mobile Security Requirements

These requirements apply to release builds. Debug convenience must not be used as evidence that production wiring is safe.

## Session material

- Access and refresh tokens must be stored using platform-protected storage.
- Android implementations must use Android Keystore-backed protection with an appropriate key lifecycle; ordinary `SharedPreferences` alone is not protected storage.
- iOS implementations must use Keychain with an access class appropriate to an unlocked user device and the bank's backup/migration policy; `NSUserDefaults` is not protected token storage.
- Logout, server revocation, and an unrecoverable authentication error must clear all session material and derived sensitive caches according to policy.
- Never log tokens, authorization headers, biometric results, Keychain/Keystore errors containing values, or complete transfer payloads.

## Biometric authentication

- Biometrics authorize a local interaction; backend authentication and transaction policy remain authoritative.
- Handle cancellation, lockout, unavailable enrollment, and system error distinctly.
- A fallback must not silently weaken the required assurance. Use an explicitly approved device-credential or server re-authentication path.
- A successful biometric result is short-lived and bound to the action shown to the customer. It must not be replayed after recreation to submit a new intent.

## Sensitive screens and operating-system surfaces

- Android transfer confirmation/result screens must apply the approved screenshot/recents protection where policy requires it.
- iOS must apply the approved application-switcher obscuring behavior; the platform does not provide an Android-identical screenshot flag.
- Notifications must not expose full account identifiers, beneficiary details, transfer amounts, or authentication state on a locked screen.
- Clipboard and accessibility behavior must be reviewed without disabling assistive technology globally.

## Deep links and push entry points

- Treat URI and notification parameters as untrusted input.
- Allow-list schemes, hosts, paths, parameter shape, and identifier length.
- Resolve an operation through authenticated local/backend state before showing sensitive details.
- A deep link may navigate to an existing operation. It must not create or confirm a transfer by itself.
- Avoid distinguishable error behavior that leaks whether another customer's operation exists.

## Network policy

- Release traffic uses TLS and the bank-approved trust policy.
- Cleartext exceptions, development certificates, and verbose HTTP bodies are restricted to explicit debug environments.
- Certificate pinning, if adopted, requires rotation and emergency-update design; it is not added casually as a single static pin.
- Connectivity callbacks are not security or availability proofs.
- Timeouts and retries must be compatible with financial idempotency and uncertain outcomes.

## Local data

- Persist only the minimum data required for product and recovery behavior.
- Treat account identifiers, beneficiary identifiers, amounts, operation identifiers, and status history as sensitive even when they are not credentials.
- Database encryption and key management follow the target threat model. If the assessment change does not implement them, document the residual risk rather than claiming ordinary app sandboxing is encryption.
- Temporary files, backups, crash attachments, screenshots, and application-switcher snapshots are part of the data boundary.

## Device-risk signals

Root/jailbreak or device-integrity signals are risk inputs, not absolute proof and not substitutes for server authorization. Any enforcement requires false-positive handling, accessibility consideration, telemetry minimization, and a support path.

## AI and development tools

Only the synthetic repository may be shared with an AI coding service. Do not submit real customer data, credentials, production tokens, internal code outside this exercise, signing keys, provisioning profiles, or private incident logs.

Generated platform-security code must be checked against current Android or Apple documentation and exercised on the relevant platform. A common test cannot establish Keychain/Keystore access-control behavior.
