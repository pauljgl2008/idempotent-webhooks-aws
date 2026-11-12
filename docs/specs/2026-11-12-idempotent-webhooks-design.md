# Idempotent webhooks on AWS — design

Date: 2026-11-12. Status: approved.

## Intent

Show how to operate a managed integration on AWS end to end: a signed webhook enters
through API Gateway, is queued in SQS, and is applied by a Java Lambda to a DynamoDB
ledger. The point is not the happy path. The point is a **bounded, stated guarantee**
that survives duplicates, retries, failures after commit and poison messages, and is
checked against an independent model instead of the system's own counters.

Infrastructure is created and destroyed with Terraform. Nothing stays running.

## The event

`contribution.recorded` — an **incremental monetary movement** (payroll giving: each
payroll run deducts an amount from one employee for one campaign).

| Field         | Type                          | Notes                               |
|---------------|-------------------------------|-------------------------------------|
| `providerId`  | string, 1–64 `[A-Za-z0-9_-]`  | who sends the webhook               |
| `eventId`     | string, 1–128 `[A-Za-z0-9_-]` | unique per provider                 |
| `employeeId`  | string, 1–64 `[A-Za-z0-9_-]`  |                                     |
| `campaignId`  | string, 1–64 `[A-Za-z0-9_-]`  |                                     |
| `amountMinor` | integer, 1 … 10^9            | minor units (cents)                 |
| `currency`    | ISO 4217, 3 uppercase letters |                                     |
| `occurredAt`  | ISO-8601 instant              | business time, not used for replay  |

Sums commute, so **order does not matter** and there is no version rule. What matters
is that each movement is applied once: losing one or applying it twice is money
counted wrong.

## Guarantee (bounded)

For the effect inside DynamoDB (one ledger entry plus the balance increment), each
`(providerId, eventId)` is applied **at most once**. A message that cannot be applied
ends in the DLQ, which has an alarm and a documented redrive: nothing is lost
silently. An external side effect (charging a card, calling another API) is out of
scope; the README names outbox + an idempotency key at the destination as the next
step. The README never says "exactly once" without naming the effect.

**Same id, different content.** The processor stores the SHA-256 of the canonical
payload next to the processed marker. Same id + same hash = duplicate → acknowledged.
Same id + different hash = conflict → not applied, the item fails, it reaches the
DLQ, and the `IdConflict` metric is emitted.

The processed marker is **permanent** (no TTL). It costs bytes; a TTL would reopen
the window the guarantee closes.

Canonical payload: the seven fields above serialized in that fixed order as
`field=value` joined by `\n`, `amountMinor` as a base-10 integer and `occurredAt` as
`Instant.toString()`. Whitespace or key order in the original JSON does not change
the hash.

## Components

```
provider ──HTTPS──▶ HTTP API ──▶ ingest (Lambda, java25, SnapStart alias)
                                   │ verify HMAC, validate, SendMessage
                                   ▼
                                 SQS standard ──(5 receives)──▶ DLQ ──▶ alarm
                                   │ batch ≤10, ReportBatchItemFailures
                                   ▼
                                 process (Lambda, java25)
                                   │ TransactWriteItems
                                   ▼
                    DynamoDB: processed_events · ledger · balances
```

### ingest

- Signature header `X-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256>` computed over
  `t + "." + rawBodyBytes` with the provider secret. The raw bytes are the request
  body exactly as received; when HTTP API marks the body as Base64, it is decoded
  first.
- Constant-time comparison (`MessageDigest.isEqual`). The timestamp of the
  **signature** must be within ±300 s of now; `occurredAt` is not checked. A late
  provider retry signs again and keeps its `eventId`.
- Responses: 401 bad or missing signature / stale timestamp; 400 body not a valid
  event; 202 only after `SendMessage` returned; 500 if SQS fails (the provider
  retries).
- The secret lives in SSM Parameter Store (SecureString, standard tier), created by
  `scripts/put-secret.sh` outside Terraform so it never reaches the state. Terraform
  grants `ssm:GetParameter` + `kms:Decrypt` (via the SSM-managed key) to ingest only.
  ingest reads it once per execution environment.

### process

Per SQS record, one `TransactWriteItems`:

1. Put `processed_events` `{pk = providerId#eventId, payloadHash, processedAt}` with
   `attribute_not_exists(pk)`.
2. Put `ledger` `{pk = employeeId#campaignId, sk = providerId#eventId, amountMinor,
   currency, occurredAt}` with `attribute_not_exists(pk)`.
3. Update `balances` `{pk = employeeId#campaignId#currency}` with
   `ADD balanceMinor :amount, entries :one`.

On `TransactionCanceledException`, read `CancellationReasons` (index 0 = marker):

| Reason on the marker      | Then                                                         |
|---------------------------|--------------------------------------------------------------|
| `ConditionalCheckFailed`  | read stored hash: equal → **duplicate, ack**; different → **conflict, item failure** + `IdConflict` |
| `TransactionConflict`     | item failure (SQS retries)                                   |
| `ThrottlingError` / `ProvisionedThroughputExceeded` | item failure                       |
| anything else / none      | item failure + log                                           |

A `ConditionalCheckFailed` on the ledger put without one on the marker is an
invariant violation: item failure + log. **Never a blanket ack.**

The handler returns `SQSBatchResponse` with only the failed message ids.
A record whose body is not a valid event is an item failure (it reaches the DLQ after
5 receives).

**Fault injection** (scenario suite only): environment variable
`FAULT_AFTER_COMMIT_EVENT_PREFIX`. When set and the `eventId` starts with it, the
handler throws **after** the transaction committed, once per execution environment per
eventId, to prove the retry does not duplicate. Unset in normal deploys.

### Tables

All three are provisioned at 5 RCU / 5 WCU (inside the always-free 25/25; on-demand is
not free). Keys are strings. No TTL anywhere.

## Configuration values

- Visibility timeout = 6 × process timeout (10 s) + batching window (0 s) → 60 s.
- `maxReceiveCount` = 5 (counts receives, not business executions).
- Event source mapping: batch 10, `maximumConcurrency` 2, `ReportBatchItemFailures`.
- HTTP API stage throttling: rate 5, burst 10.
- Log retention 3 days. EMF metrics have no `eventId` dimension.
- Budget alert at USD 1 (a warning; AWS refreshes it every 8–12 h, it is not a cap).

## IAM and deployment

- Bootstrap (run once by the account owner with SSO temporary credentials, no access
  keys): S3 state bucket with `use_lockfile`, GitHub OIDC provider, deploy role and
  permissions boundary.
- Trust: `aud = sts.amazonaws.com`, `sub = repo:pauljgl2008/idempotent-webhooks-aws:environment:aws`.
  The `aws` environment deploys only from `main`, with required reviewer.
- `id-token: write` only on the deploy job. PR CI never has AWS credentials.
- Deploy role limited to resources prefixed `iwh-`, `iam:PassRole` only to
  `iwh-*` Lambda roles, and every role it creates must carry the boundary.
- Destroy runs as its own job with `always()`, workflow `concurrency` allows one
  deployment at a time, and `scripts/verify-empty.sh` lists Lambda, SQS, DynamoDB and
  API Gateway by prefix and type in the region.

## Verification

- Unit: HMAC, signature header parsing, event validation, canonical hash,
  cancellation classification.
- Integration (Testcontainers + DynamoDB Local): the transaction, duplicate,
  conflict, concurrent duplicates.
- In AWS (`scenarios` module, run against a deployed stack): parallel duplicate,
  conflict → DLQ, failure after commit, poison put straight on the queue → DLQ →
  redrive, partial batch, ~1,000 events with duplicates. Each scenario compares the
  final DynamoDB state with `ExpectedLedger`, an independent model computed from the
  scenario's own event list without any code from `ingest` or `process`.

## Non-goals and anti-goals

Out of scope: FIFO queues, Step Functions, multi-region, Kafka, a dashboard, external
side effects. It fails even if it works when: keys or the secret land in the repo or
the state, cost goes over USD 1, the history is invented, or the README promises
"exactly once" without bounding the effect.

## Decisions

- **Standard queue, not FIFO.** Movements commute, and idempotency has to be durable
  without relying on the queue. FIFO deduplicates for 5 minutes and does not make the
  consumer idempotent.
- **Plain transaction, not Powertools idempotency.** The marker and the effect commit
  together; a separate idempotency store with its own expiry would reintroduce the
  gap between "marked" and "applied".
- **Java 25 managed runtime, AWS SDK v2, no framework.** Small handlers; SnapStart on
  the ingest alias.
- **No LocalStack, no ElasticMQ.** DynamoDB Local covers the transaction; SQS and
  Lambda semantics are tested in AWS itself.
