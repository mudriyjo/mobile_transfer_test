# Business Context

## Product

Mobile Bank is a synthetic pilot application for retail customers. It supports:

- viewing accounts and cached balances;
- saving and selecting beneficiaries;
- making an immediate transfer after biometric confirmation;
- scheduling a payment for later execution;
- observing the status of submitted financial operations.

Accounts are the reference flow for durable state and UI restoration. Instant transfer is the primary flow for this assessment. Scheduled payments deliberately reuse transfer infrastructure, so changes to request identity, serialization, repositories, clocks, or reconciliation can affect both products.

## Pilot incident

The following sequence occurred during the pilot:

1. A customer reviewed a transfer and passed biometric confirmation.
2. The mobile app sent a create-transfer request.
3. The backend accepted the operation and started processing it.
4. The connection ended before the response reached the app.
5. The app entered the background and the operating system later removed its process.
6. After reopening, the app displayed the transfer as failed.
7. The customer selected **Retry**.
8. The client submitted a newly generated operation identifier.
9. The backend treated the request as a different intent.
10. A second transfer was executed.

This is an ambiguous distributed outcome: a transport error only describes what the client observed. It does not establish whether the server committed the request.

## Additional pilot reports

The pilot team also reported that:

- confirmation can remain in a loading state on some devices;
- a navigation transition may repeat after rotation or re-entering a screen;
- account identifiers and amounts appear in analytics or application logs;
- one or more platform session-storage implementations may not meet the bank's protection requirements;
- the offline label can disagree with the authoritative operation status;
- Android and iOS may restore an unfinished transfer differently.

These reports are leads, not automatically equal priorities. Establish a concrete failure sequence and repository evidence before including one in the top three risks.

## Business invariants

The following invariants are authoritative for the exercise:

- One customer intent must have one stable operation identifier.
- The same operation identifier must not represent two different payloads.
- The app must not describe an ambiguous submission as a confirmed business rejection.
- A transport retry, UI recreation, or process restoration must not silently create a new customer intent.
- A terminal status comes from the backend status contract, not from connectivity state.
- Durable business state must be recoverable independently of a particular screen or in-memory presentation object.
- A transfer may be resubmitted only under a protocol that preserves the same intent identity and obeys the backend idempotency contract.

## Pilot release decision

The next pilot expansion is planned soon. The release recommendation must therefore distinguish:

- a safety requirement that blocks money movement;
- an operational mitigation or staged-rollout condition;
- an important but deferrable improvement;
- a platform assumption that must be verified on a simulator or device.

A mobile binary already installed on user devices cannot be rolled back instantly. A credible plan should include server compatibility, a remote feature gate or kill switch where applicable, observable rollout criteria, and containment for older clients.

## Synthetic-data boundary

All repository data is fabricated. Never add real names, account numbers, tokens, credentials, production hosts, crash exports, or internal source code from another system. The same boundary applies to AI tools.
