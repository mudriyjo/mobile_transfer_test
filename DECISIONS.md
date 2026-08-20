# Problem

Describe the concrete failure sequence addressed by this change.

# User and Production Impact

Explain the possible effect on the customer, bank, operation status, and support/operations.

# Chosen Scope

Define the vertical slice implemented within the timebox and its invariant.

# Decisions

Record the important decisions and why they are safe under timeout, cancellation, repeated input, and restoration where applicable.

# Shared vs Platform-specific Responsibility

State what belongs to common code and what remains Android- or iOS-specific. Explain any platform-specific assumptions.

# Alternatives

Describe the material alternatives considered and why they were rejected or deferred.

# Validation

List each command and result. Separate device/simulator runs, automated tests, static analysis, and checks not performed.

# Residual Risks

List the material risks that remain and the condition under which each becomes relevant.

# Non-goals

List intentionally excluded work.

# Release Recommendation

**Decision:** `GO | CONDITIONAL GO | NO-GO`

State what is safe, mandatory pre-release conditions, compatible app/backend versions, required Android/iOS checks, and post-release metrics.

# Rollout and Rollback

Describe feature flags or staged rollout, the remote kill switch or containment action, and how to recover when an already-installed mobile version cannot be immediately rolled back.
