# AI-Enabled Take-Home Assignment

## Objective

You are joining a mobile banking pilot that has experienced an ambiguous transfer outcome. A user confirmed a transfer, the backend accepted it, and the network connection was lost before the client received the response. The app later displayed a failure. After process termination and a manual retry, the mobile client submitted a different operation identifier and the backend executed a second transfer.

Your task is to analyze the existing instant-transfer implementation and make one minimal, production-oriented change that reduces the risk of duplicate execution or loss of the correct operation status after a lifecycle interruption.

The repository also contains reports of an indefinite loading state, repeated navigation after recreation, sensitive values in telemetry, weak platform token storage, inaccurate offline state, and differences between Android and iOS restoration. You are not expected to fix all of them.

## Required work

Within the timebox:

1. Reconstruct the main transfer execution path.
2. Identify the three most material production risks and cite repository evidence.
3. Select one critical vertical slice.
4. Implement the smallest safe change in shared code and, only if required, platform code.
5. Add at least two meaningful checks.
6. Assess Android, iOS, and scheduled-payment impact.
7. Document residual risks and unverified assumptions.
8. Recommend `GO`, `CONDITIONAL GO`, or `NO-GO` for the next pilot.
9. Record how AI was used and how its output was independently checked.

A focused, proven change is valued more highly than a broad refactor.

## Timebox and checkpoints

The take-home assignment has a total timebox of **four hours (240 minutes)**.

### 0–25 minutes: analysis

Complete the first version of `PLAN.md`. Cite important evidence as `relative/path/File.kt:line`. At 25 minutes the assessment system records that version; do not rewrite its conclusions later. You may add a clearly labeled addendum if subsequent evidence changes an assumption.

### 25–180 minutes: implementation

Implement one vertical slice. Include durable state or a platform change only when it is necessary for the selected invariant. Keep adjacent product behavior compatible.

### 180–220 minutes: validation

Run the relevant baseline and new tests, an Android/JVM verification, and an iOS framework or common/native check where the environment permits it. Exercise the failure path, not only the success path. Record commands and results.

### 220–240 minutes: decision

Finish `DECISIONS.md`, `AI_USAGE.md`, and the release recommendation. Run the submission and change-budget scripts. The assessment system accepts the server-side commit at the 240-minute deadline; uncommitted local changes are not assessed.

## Deliverables

### Code and tests

- A bounded production diff that closes one causal chain end to end.
- At least one unit or integration test for the selected business invariant.
- At least one failure, lifecycle, restoration, or platform-oriented test.
- No disabled baseline tests or weakened assertions.

Useful failure scenarios include, but are not limited to:

- a stable operation identifier across a retry;
- rejection of the same identifier with a different payload;
- an ambiguous network outcome that is not presented as a confirmed failure;
- recovery of an unfinished operation after recreation;
- a repeated tap that does not create a second operation;
- status reconciliation instead of resubmission;
- a navigation effect that is not replayed after recreation;
- analytics that excludes sensitive attributes;
- platform-protected session storage.

### `DECISIONS.md`

Explain the problem, user and bank impact, chosen scope, shared/platform ownership, alternatives, actual validation, residual risks, non-goals, rollout, and rollback.

Clearly distinguish:

- behavior run on a device/simulator;
- behavior established by a test;
- behavior established only by code or documentation review;
- platform-specific behavior that remains unverified.

### `AI_USAGE.md`

Name the tools and purposes, two suggestions you accepted, at least one suggestion you rejected or materially changed, how generated output was checked, and one platform-specific assumption verified through documentation or experiment. Confirm that no restricted data was provided to a model. A full prompt transcript is not required.

### Release recommendation

Choose exactly one: `GO`, `CONDITIONAL GO`, or `NO-GO`. State what is safe, what remains mandatory before release, required platform checks, post-release telemetry, rollback or kill-switch behavior, and mobile/backend compatibility.

## Product and technical context

The application supports account balances, beneficiaries, instant transfers, scheduled payments, and operation status. Accounts are the reference implementation for cache and state restoration. Scheduled payments share transfer infrastructure, so a shared client or operation-identifier change must be checked against that path.

Relevant contracts are under `docs/`. The backend stub deliberately supports deterministic success, rejection, ambiguous-outcome, and status-recovery scenarios. Its contract is authoritative for this exercise; do not assume that connectivity implies service availability or that a network exception proves no server-side commit.

## AI policy

Use an AI coding assistant during the take-home. Appropriate uses include repository mapping, tracing Flow/coroutine ownership, comparing platform implementations, generating a test draft, reviewing Compose effects, and checking security or performance assumptions.

You must independently validate generated code, verify platform API claims, and be able to explain every submitted change. Do not send any AI service real banking data, production credentials, user tokens, personal data, or code outside this synthetic repository.

## Evaluation

The take-home contributes 25–30% of the practical stage. The live defense confirms all hard gates.

| Area | Take-home weight |
|---|---:|
| Repository model | 15% |
| Production-risk prioritization | 20% |
| Minimality and correctness | 25% |
| Tests and validation | 20% |
| Android/iOS impact | 10% |
| Residual risks and release decision | 5% |
| Responsible AI use | 5% |

You are not evaluated on the number of files read, minor findings, visual polish, or test count beyond meaningful coverage.

## Boundaries

- Do not rewrite the entire architecture.
- Do not modify or delete baseline tests merely to make the build pass.
- Do not add production credentials or real endpoints.
- Relate client-side guarantees to the documented backend idempotency contract.
- State explicitly which data survives configuration changes, process death, force quit, and app relaunch.
- Do not commit IDE state, build outputs, local databases, signing material, or prompt transcripts containing sensitive data.
- No screen recording or complete prompt history is required.
- The submission is used only as an assessment artifact, not as production bank code.
