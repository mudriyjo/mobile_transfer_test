# Analytics and Logging Contract

Telemetry must help operators understand reliability without creating a second store of financial or personal data.

## Event model

Allowed transfer events are low-cardinality product milestones:

- `transfer_confirmation_shown`;
- `transfer_authentication_result`;
- `transfer_submission_started`;
- `transfer_submission_outcome`;
- `transfer_reconciliation_started`;
- `transfer_status_changed`;
- `transfer_result_shown`.

Recommended attributes:

| Attribute | Allowed values |
|---|---|
| `result` | small enum such as `success`, `cancelled`, `rejected`, `unknown`, `error` |
| `status` | documented status enum |
| `network_class` | coarse enum such as `offline`, `wifi`, `cellular`, `unknown` |
| `platform` | `android` or `ios` |
| `app_version` | released version/build |
| `recovery_source` | `launch`, `foreground`, `background`, `manual` |
| `duration_bucket` | bounded buckets, not exact user timing |
| `error_category` | sanitized stable enum |

## Prohibited fields

Do not send or log:

- full or partial account/IBAN/card identifiers;
- beneficiary names or identifiers;
- exact transfer amounts or free-text payment descriptions;
- access/refresh tokens or authorization headers;
- operation IDs, transfer IDs, device advertising IDs, or other per-operation high-cardinality keys;
- raw request/response bodies;
- raw exception messages that may contain URLs, payloads, identifiers, or platform secrets;
- biometric data or details beyond a coarse outcome category.

Hashing a stable financial identifier does not automatically make it suitable analytics data. It can remain linkable and high-cardinality.

## Logging

- Logs use a small, reviewed category and a random short-lived diagnostic correlation value when correlation is necessary.
- Error mapping removes server/body details before rendering or logging.
- Debug HTTP body logging must be excluded from release and must still use only synthetic data during development.
- Crash breadcrumbs record state transitions and error categories, not payloads.
- Avoid using user-controlled strings as log formats or analytics keys.

## Delivery semantics

- Recomposition, screen recreation, and a new collector must not duplicate a milestone event.
- An event that represents durable business status should be derived or acknowledged against a durable transition, not emitted solely because a screen became visible.
- Telemetry delivery is best effort and never controls transfer correctness.
- Analytics failure must not block or retry a financial operation.

## Operational signals

Product analytics and operational metrics have different purposes. The pilot should monitor aggregate counts/rates for:

- submissions entering an unknown outcome;
- non-terminal operations by age bucket;
- reconciliation attempts and status lookup error categories;
- duplicate-key same-payload responses;
- payload conflicts for an existing key;
- confirmation-to-terminal duration buckets;
- platform/version crash-free sessions, hangs/ANRs, and launch failures.

Never put operation or customer identifiers in metric labels. Investigation that requires an individual operation belongs in an access-controlled audit/support system outside mobile analytics.

## Audit boundary

The backend owns the authoritative financial audit trail. Mobile telemetry may show that a client attempted, restored, or displayed a state; it cannot prove that money moved. Release analysis must compare aggregate mobile recovery signals with backend authoritative status metrics.
