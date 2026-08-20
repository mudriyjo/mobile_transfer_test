# Critical User Flow

Describe the principal user scenario and list its execution path from the confirmation UI to the backend request, local persistence, restoration/reconciliation, and result navigation. Cite the most important evidence as `relative/path/File.kt:line`.

<!-- Complete this first version within the first 25 minutes. Do not replace it later; append a dated addendum if evidence changes an assumption. -->

# Relevant Components

List the shared, Android, iOS, backend-contract, persistence, lifecycle, and navigation components that materially affect the flow.

# Top Risks

Rank three production risks. For each, provide a concrete failure sequence, user/bank impact, likelihood conditions, and repository evidence.

1. **Risk:**
   - Scenario:
   - Impact:
   - Conditions:
   - Evidence:
2. **Risk:**
   - Scenario:
   - Impact:
   - Conditions:
   - Evidence:
3. **Risk:**
   - Scenario:
   - Impact:
   - Conditions:
   - Evidence:

# Chosen Slice

State the single invariant you will establish, why it has the highest value in this timebox, and the expected production/test files affected.

# Validation Plan

Name at least two deterministic checks: one for the main invariant and one for a failure, lifecycle, restoration, or platform case. State which externally meaningful outcomes they assert.

# Platform Impact

Explain the expected Android and iOS behavior, what is shared, and which platform guarantee requires separate verification. Include scheduled-payment impact.

# Non-goals

List issues intentionally left outside this change.
