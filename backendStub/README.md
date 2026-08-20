# Banking backend stub

This module is a synthetic Ktor backend for the mobile assessment. It keeps an
in-memory transfer ledger and an append-only journal. It deliberately exposes
deterministic failure controls; it is not intended for production deployment.

## API

- `GET /health`
- `GET /v1/accounts`
- `GET /v1/beneficiaries`
- `POST /v1/transfers` with an `Idempotency-Key` header
- `GET /v1/transfers/by-operation/{operationId}`
- `GET /v1/transfers/{transferId}`
- `GET /__control/faults`
- `PUT /__control/faults`
- `GET /__control/journal`
- `POST /__control/release-blocked`
- `POST /__control/reset`

The transfer body uses integer minor units:

```json
{
  "fromAccountId": "acc-checking-eur",
  "toAccountId": "ben-alex",
  "amountMinorUnits": 1250,
  "currency": "EUR",
  "reference": "Dinner"
}
```

For compatibility with the synthetic mobile read model, `toAccountId` accepts
either a beneficiary identifier returned by `/v1/beneficiaries` or that
beneficiary's underlying account identifier.

The same idempotency key and semantically identical payload return the original
transfer. Reusing the key with a different payload returns `409 Conflict`.

## Deterministic faults

`PUT /__control/faults` accepts a `FaultPlan`. Submit modes are `NORMAL`,
`REJECT_BEFORE_COMMIT`, `COMMIT_THEN_TIMEOUT`, `COMMIT_THEN_MALFORMED_RESPONSE`,
and `BLOCK_AFTER_COMMIT`. Non-normal submit faults apply once unless
`submitModeApplications` is increased. Status lookups can return a configured
number of temporary failures before advancing the operation to a terminal
status.

For a real client timeout, set `submitDelayMillis` above the client's request
timeout while using `COMMIT_THEN_TIMEOUT`. The transfer is committed before the
delay, so it remains discoverable through the status endpoint.

The control API is enabled by default. Set `BACKEND_CONTROLS_ENABLED=false` to
disable every `__control` route. `PORT` defaults to `8080`.
