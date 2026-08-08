# Implementation Specification

## Cobre Notification Delivery

**Status:** Implemented

---

## 1. Purpose

This specification defines the technical behavior, architecture, contracts, invariants, failure semantics, security constraints, and validation criteria implemented by the Cobre Notification Delivery service.

The document describes the implementation decisions behind the repository and complements the system design, security, usage, and reconstruction documentation.

The system is designed to reliably deliver platform events to subscribed client webhooks while maintaining tenant isolation, delivery history, retry semantics, security controls, and operational recovery mechanisms.

---

# 2. Scope

The service provides:

* asynchronous event ingestion
* durable notification persistence
* asynchronous webhook delivery
* at-least-once delivery semantics
* retry handling for transient delivery failures
* delivery attempt history
* manual replay of failed notifications
* client-specific subscriptions
* HMAC-SHA256 webhook signing
* tenant-isolated API access
* OIDC authentication
* cursor-based pagination
* SSRF protection
* rate limiting
* structured logging and metrics
* operational DLQ handling

The service does not:

* interpret business semantics of event payloads
* provide exactly-once delivery to external systems
* guarantee global ordering
* store webhook secrets in DynamoDB
* use the DLQ as part of normal webhook retry exhaustion

---

# 3. System Components

The repository is organized around three deployable applications:

```text
consumer
worker
api
```

and a shared library:

```text
cobre-notificacion-kit-lib
```

The responsibilities are:

### Consumer

Consumes events from Kafka/Redpanda, persists notifications, and schedules asynchronous delivery.

### Worker

Consumes delivery work from SQS, resolves subscriptions, signs and sends webhook requests, records attempts, and manages delivery state.

### API

Provides authentication, notification queries, replay operations, and subscription management.

### Shared Library

Contains shared domain models, ports, application abstractions, and common functionality required by the services.

---

# 4. High-Level Architecture

The event flow is:

```text
                    ┌──────────────────┐
                    │  Event Producer  │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ Kafka / Redpanda │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │     Consumer     │
                    └───────┬──────────┘
                            │
                  ┌─────────┴─────────┐
                  ▼                   ▼
             DynamoDB                SQS
                  ▲                   │
                  │                   ▼
                  │              ┌─────────┐
                  └──────────────│ Worker  │
                                 └────┬────┘
                                      │
                                      ▼
                               Client Webhook
```

The API operates independently from the asynchronous delivery path:

```text
Client
   │
   ▼
 API
   │
   ├── DynamoDB
   │
   └── Replay → SQS
```

Kafka and SQS intentionally have different responsibilities.

Kafka is the event ingestion mechanism.

SQS is the delivery work queue and retry mechanism.

---

# 5. Architectural Style

The implementation follows a hexagonal architecture.

The main conceptual boundaries are:

```text
Domain
Application
Infrastructure
```

Domain and application logic communicate with infrastructure through ports.

Infrastructure adapters provide implementations for:

* Kafka
* SQS
* DynamoDB
* Secrets Manager
* HTTP
* OIDC
* observability

The domain/application layer SHALL remain independent of infrastructure-specific implementations.

Architecture tests SHALL prevent forbidden dependencies from entering the domain/application boundaries.

---

# 6. Event Ingestion

The consumer SHALL process an incoming event in the following order:

```text
Kafka message
    ↓
deserialize
    ↓
validate
    ↓
persist notification
    ↓
enqueue event_id
    ↓
acknowledge Kafka message
```

Kafka acknowledgment SHALL occur only after the notification has been persisted.

The delivery payload placed into SQS contains the event identifier rather than duplicating the complete event payload.

This allows the worker to retrieve the latest notification state from DynamoDB when it processes the message.

---

# 7. Idempotent Ingestion

The notification event identifier is the logical identity of an event.

The persistence layer SHALL enforce idempotent creation.

If the same event is received multiple times:

```text
event_id = X
event_id = X
event_id = X
```

the system SHALL maintain one logical notification rather than creating multiple independent notifications.

This protects against Kafka redelivery and consumer retries.

---

# 8. Notification State Machine

The implemented notification lifecycle includes:

```text
COMPLETED
RETRYING
FAILED
DISCARDED
```

The main transitions are:

```text
                 ┌─────────────┐
                 │   PENDING   │
                 └──────┬──────┘
                        │
          ┌─────────────┼─────────────┐
          │             │             │
          ▼             ▼             ▼
      COMPLETED      RETRYING      DISCARDED
                        │
                        ▼
                    COMPLETED
                        │
                        ▼
                      FAILED
                        │
                        ▼
                    REPLAY
                        │
                        ▼
                    RETRYING
```

The exact transition depends on the delivery outcome.

A notification with no applicable subscription is discarded rather than treated as a webhook delivery failure.

---

# 9. Subscription Resolution

Before delivery, the worker SHALL resolve an active subscription for the client and event type.

The implementation supports event-specific subscriptions and wildcard subscriptions.

Conceptually:

```text
client + event_type
       ↓
specific subscription
       ↓
fallback wildcard subscription
```

If no active subscription exists, the event SHALL transition to:

```text
DISCARDED
```

This is a business outcome rather than an infrastructure failure.

---

# 10. Persistence Model

DynamoDB is used as the durable state store.

The notification data model uses event identifiers as the primary logical key.

The implementation uses patterns based on:

```text
EVENT#{event_id}
```

Delivery attempts are represented as separate items rather than an ever-growing embedded array.

This keeps the notification item bounded and allows delivery history to grow independently.

The subscriptions model uses:

```text
CLIENT#{client_id}
```

and supports access patterns required by client and event-type queries.

A client-created secondary index is used for notification listing:

```text
gsi_client_created
```

---

# 11. Delivery Attempts

Every delivery attempt SHALL be recorded.

An attempt records information necessary to understand the delivery result, including:

* event identifier
* attempt number
* replay cycle
* timestamp
* HTTP response information when available
* error information when available

Attempts are retained independently from the notification metadata.

Replay SHALL preserve the previous attempt history.

---

# 12. Optimistic Concurrency

Notification state transitions SHALL use conditional persistence operations to protect against concurrent workers and duplicate queue deliveries.

The implementation must account for:

* duplicate SQS delivery
* worker concurrency
* replay races
* worker restarts

A worker SHALL NOT assume that receiving an SQS message makes it the only process operating on that notification.

---

# 13. Backlog Counters

Backlog counters SHALL avoid a single globally hot DynamoDB item.

The implementation distributes counters across partitions.

Counter updates SHALL remain consistent with notification state transitions.

This allows backlog information to scale with notification throughput without creating a single DynamoDB write bottleneck.

---

# 14. Retry Policy

Webhook delivery failures are classified into retryable and permanent failures.

Retryable failures include:

```text
408
429
5xx
timeouts
connection failures
```

Permanent failures include, among others:

```text
400
404
```

The configured retry delays are:

```text
5 seconds
30 seconds
2 minutes
10 minutes
15 minutes
```

Jitter of up to 20% is applied to retry delays.

Retry scheduling is performed through SQS rather than through worker thread sleeping or an in-memory scheduler.

---

# 15. Retry State

When a retryable failure occurs:

```text
delivery attempt
      ↓
record attempt
      ↓
update notification
      ↓
schedule retry
```

The notification SHALL represent its retrying state through the persisted state model.

The queue provides the durability of the scheduled retry.

A worker restart therefore does not remove pending retry work.

---

# 16. Permanent Delivery Failure

When a webhook returns a permanent failure response, the notification SHALL transition to:

```text
FAILED
```

No additional webhook retry SHALL be scheduled.

The delivery attempt SHALL remain available for inspection.

The notification SHALL remain eligible for manual replay according to the replay rules.

---

# 17. Retry Exhaustion

When all configured retries have been consumed, the notification SHALL transition to:

```text
FAILED
```

The notification remains persisted.

Its delivery history remains queryable.

The notification can subsequently be replayed through the API.

Retry exhaustion SHALL NOT route the message to the DLQ.

This distinction is intentional:

```text
Webhook failure
      ↓
Retry policy
      ↓
Retry exhausted
      ↓
FAILED
      ↓
Manual replay
```

---

# 18. Dead Letter Queue

The DLQ is reserved for technical processing failures.

It is NOT the final destination for normal webhook failures.

Examples of technical failures include:

* malformed SQS payloads
* unrecoverable deserialization errors
* unexpected worker processing failures
* failures that prevent the worker from safely completing message processing

Normal webhook failures do not enter the DLQ.

This includes:

```text
400
404
408
429
5xx
timeout
connection failure
retry exhaustion
```

The configured queue redelivery policy allows an unsuccessfully processed message to be retried before it is moved to the DLQ.

The repository configuration uses the fifth receive as the redrive threshold.

DLQ messages require manual operational intervention.

The on-call team is responsible for investigating and deciding whether a message should be redriven.

The system SHALL NOT automatically create an infinite DLQ replay loop.

---

# 19. Manual Replay

Manual replay is exposed through:

```http
POST /notification_events/{id}/replay
```

Replay is allowed only when the notification is in:

```text
FAILED
```

state.

The API SHALL verify:

1. authentication
2. required replay permission
3. tenant ownership
4. notification existence
5. notification state

If the notification cannot be replayed because it is not in the required state, the API returns:

```http
409 Conflict
```

A successful replay:

```text
FAILED notification
       ↓
increment replay cycle
       ↓
preserve previous attempts
       ↓
enqueue event_id
       ↓
return 202 Accepted
```

Replay uses the same worker delivery pipeline as normal delivery.

---

# 20. Replay Semantics

Replay SHALL NOT reset historical data.

The system SHALL retain:

```text
original attempts
+
replay attempts
```

Replay count is tracked separately from attempt number.

This makes it possible to distinguish:

```text
initial delivery cycle
```

from:

```text
manual recovery cycle
```

without destroying the original delivery evidence.

---

# 21. Webhook Signing

Webhook requests SHALL use HMAC-SHA256 signing.

The signed payload is:

```text
timestamp + "." + raw_body
```

The signature is generated using the subscription's signing secret.

The raw HTTP body SHALL be used for signing rather than a reserialized JSON representation.

This guarantees that both sides calculate the signature over the same bytes.

The request contains the timestamp and signature through the webhook authentication headers defined by the implementation.

---

# 22. Secret Management

Webhook signing secrets SHALL NOT be stored directly in DynamoDB.

Subscriptions store a secret reference.

The worker resolves the secret from the configured secrets provider before generating the HMAC signature.

The production-oriented secret provider is AWS Secrets Manager.

The local environment provides a compatible local implementation.

Secrets SHALL NOT appear in application logs.

---

# 23. Secret Caching

The worker may cache resolved secrets for a short bounded period to avoid excessive calls to the secrets provider.

The trade-off is intentional:

```text
lower secrets-provider traffic
        vs
bounded propagation delay
```

Secret cache behavior SHALL NOT change the security boundary of the system.

---

# 24. SSRF Protection

Webhook URLs are customer-controlled and therefore untrusted.

Before each outbound HTTP request, the worker SHALL validate the destination.

The implementation SHALL:

* require HTTPS
* resolve the destination
* reject loopback addresses
* reject localhost
* reject private address ranges
* reject link-local addresses
* reject internal network targets
* prevent unsafe redirects

Redirects SHALL NOT be followed automatically.

Destination validation SHALL occur at delivery time, not only when a subscription is created.

This protects against DNS changes and destination rebinding between subscription creation and delivery.

---

# 25. API Endpoints

The implemented API exposes the following functional areas.

### Authentication

```http
POST /oauth/token
```

### Notification events

```http
GET /notification_events
GET /notification_events/{id}
POST /notification_events/{id}/replay
```

### Subscriptions

```http
POST /subscriptions
GET /subscriptions
```

### Operational endpoints

The API also exposes the health/info endpoints defined by the implementation.

Health and informational endpoints have intentionally different authentication requirements from protected business endpoints.

---

# 26. Cursor Pagination

Notification listing uses cursor-based pagination.

The client-facing cursor is opaque.

The cursor contains the information required to continue the underlying DynamoDB query without exposing internal database implementation details.

Cursors are tenant-bound.

A cursor generated for one client SHALL NOT be usable by another client.

Malformed or invalid cursors are rejected rather than being interpreted as unrestricted queries.

Notification ordering uses a canonical timestamp representation compatible with lexicographical ordering.

---

# 27. Tenant Isolation

The authenticated token determines the effective client identity.

The API SHALL NOT trust a caller-provided client identifier as the authorization boundary.

Queries are scoped to the authenticated client.

When a client requests another client's resource, the implementation returns:

```http
404 Not Found
```

rather than exposing whether the resource exists.

This behavior applies to notification access and other tenant-owned resources.

---

# 28. Authentication

Authentication uses OIDC.

The API validates:

* JWT signature
* token validity
* issuer
* scopes

The configured issuer is part of the trust boundary.

A valid JWT signature from an untrusted issuer SHALL NOT be accepted.

The service does not maintain its own client password database.

---

# 29. Authorization

Authorization is scope-based and tenant-aware.

The implemented authorization model includes scopes such as:

```text
SCOPE_READ
SCOPE_REPLAY
subscriptions:manage
```

according to endpoint requirements.

Protected endpoints are closed by default unless explicitly configured otherwise.

Authentication and authorization are separate checks:

```text
Authentication
    ↓
Who is the caller?

Authorization
    ↓
What can this caller do?
```

---

# 30. Rate Limiting

The API implements rate limiting for authenticated requests.

The token endpoint has a stricter policy than normal API traffic.

The objective is to prevent abuse of:

* business APIs
* authentication infrastructure

Rate limits are enforced before expensive downstream processing.

---

# 31. Observability

The implementation provides structured logging and metrics.

Important correlation information includes:

```text
event_id
client_id
request_id
```

Sensitive information SHALL NOT be logged.

This includes:

* signing secrets
* authorization credentials
* client credentials
* sensitive authentication material

Notification payloads should not be emitted into operational logs unless explicitly required and safely redacted.

---

# 32. Metrics

The implementation provides observability for important delivery behavior, including:

* received notifications
* successful deliveries
* failed deliveries
* delivery attempts
* retries
* retry exhaustion
* replay operations
* webhook latency
* webhook response status
* queue depth
* processing failures

These metrics allow operators to distinguish between:

```text
system-wide delivery problems
```

and:

```text
individual customer endpoint problems
```

---

# 33. Operational Alerts

The monitoring configuration includes alerting for delivery failures.

The system distinguishes between:

### Global delivery degradation

A sustained increase in delivery failures may indicate a platform-wide problem.

### Customer-specific failures

Repeated failures for a particular client destination may indicate that the customer's endpoint is unavailable or misconfigured.

This distinction is important because the operational response differs between platform incidents and customer endpoint incidents.

---

# 34. Local Development Environment

The repository provides a local environment using Docker Compose and compatible local infrastructure.

The environment includes:

```text
Redpanda
ElasticMQ
LocalStack / DynamoDB
Keycloak
Elasticsearch
Kibana
Grafana
```

These components allow the complete delivery flow to be exercised locally without requiring production cloud infrastructure.

---

# 35. Infrastructure Initialization

Local infrastructure initialization is designed to be repeatable.

Production runtime services SHALL NOT require application-level infrastructure creation permissions.

Infrastructure creation and runtime operation are treated as separate responsibilities.

This reduces the permissions required by production application identities.

---

# 36. Testing Strategy

Testing is organized by risk and system boundary.

## Unit Tests

Unit tests cover:

* notification state transitions
* retry classification
* retry timing
* jitter
* replay behavior
* subscription resolution
* HMAC signing
* tenant authorization
* cursor validation

## Architecture Tests

Architecture tests verify dependency boundaries.

The domain/application layer SHALL NOT acquire forbidden infrastructure dependencies.

## Integration Tests

Integration tests validate interactions with:

* Kafka/Redpanda
* SQS/ElasticMQ
* DynamoDB
* OIDC/Keycloak
* outbound HTTP

## End-to-End Tests

End-to-end tests validate:

```text
event
 ↓
Kafka
 ↓
consumer
 ↓
DynamoDB
 ↓
SQS
 ↓
worker
 ↓
webhook
 ↓
attempt history
```

---

# 37. Security Validation

The implementation explicitly addresses several high-risk security categories.

### Broken access control

Controls:

* authenticated client identity
* tenant-scoped queries
* resource ownership
* 404 responses for foreign resources
* tenant-bound cursors

### SSRF

Controls:

* HTTPS-only destinations
* IP validation
* private network rejection
* loopback rejection
* link-local rejection
* redirect restrictions

### Authentication failures

Controls:

* OIDC
* JWT signature validation
* issuer validation
* scope validation

### Sensitive data exposure

Controls:

* secrets stored outside DynamoDB
* secrets excluded from logs
* credentials excluded from logs
* notification data handled without unnecessary logging

---

# 38. Performance and Scalability

The architecture intentionally separates workloads with different scaling characteristics.

Kafka consumption scales according to event throughput and consumer lag.

SQS workers scale according to delivery workload and queue depth.

The API scales according to HTTP traffic.

Webhook latency does not block Kafka consumption.

A slow customer endpoint therefore consumes worker capacity rather than directly blocking event ingestion.

DynamoDB access patterns are based on known query requirements rather than table scans.

---

# 39. Failure Model

The implementation distinguishes two fundamental categories.

## Customer delivery failure

The platform successfully processes the notification, but the destination cannot accept it.

```text
Webhook
   ↓
failure
   ↓
retry
   ↓
FAILED
   ↓
manual replay
```

## Platform processing failure

The platform cannot safely process the queue message.

```text
SQS
   ↓
worker processing failure
   ↓
redelivery
   ↓
DLQ
   ↓
on-call investigation
   ↓
manual redrive
```

This distinction is a core architectural invariant.

---

# 40. Architectural Invariants

The following invariants SHALL remain true:

### Invariant 1 — No lost notification on normal retryable failure

A transient webhook failure SHALL result in retry processing rather than immediate loss.

### Invariant 2 — Retry exhaustion is not a DLQ event

A customer's unavailable webhook SHALL result in:

```text
FAILED
```

not:

```text
DLQ
```

### Invariant 3 — DLQ represents processing failure

The DLQ SHALL indicate that the delivery system could not safely process the queue message.

### Invariant 4 — Tenant isolation

A client SHALL NOT access another client's notifications or subscriptions.

### Invariant 5 — Secrets remain outside business persistence

The notification database SHALL contain secret references rather than signing secrets.

### Invariant 6 — Replay preserves history

Manual replay SHALL NOT destroy the original delivery attempt history.

### Invariant 7 — Delivery remains asynchronous

API requests SHALL NOT wait synchronously for external webhook delivery.

---

# 41. Implementation Milestones

The implementation is structured around the following dependency order:

```text
1. Project foundation
2. Domain model
3. Application ports and use cases
4. Persistence
5. Kafka consumer
6. SQS worker
7. Retry semantics
8. DLQ semantics
9. Webhook signing
10. SSRF protection
11. Subscription management
12. API
13. Authentication and authorization
14. Observability
15. Local infrastructure
16. Integration testing
17. End-to-end testing
18. Security validation
19. Final hardening
```

Each stage is intended to preserve a buildable and testable repository.

---

# 42. AI-Assisted Development

AI was used as a productivity multiplier during implementation.

AI assistance was used for activities including:

* implementation scaffolding
* boilerplate generation
* test generation
* edge-case discovery
* code review
* security review
* documentation
* refactoring
* exploring implementation alternatives

AI-generated suggestions were treated as proposals rather than authoritative decisions.

Architectural, reliability, security, and product decisions remained engineering decisions.

The AI usage evidence associated with the challenge should document relevant prompts, generated proposals, decisions taken, rejected alternatives, and validation performed.

The important distinction is:

```text
AI proposal
     ↓
Engineering review
     ↓
Implementation
     ↓
Automated validation
```

rather than treating generated code as automatically correct.

---

# 43. Acceptance Criteria

The implementation SHALL satisfy the following scenarios.

## Successful delivery

```text
event published
→ notification persisted
→ event queued
→ worker delivers
→ webhook returns 2xx
→ attempt recorded
→ notification completed
```

## Retryable failure

```text
webhook returns 503
→ retry scheduled
→ subsequent attempt
→ delivery succeeds
```

## Permanent failure

```text
webhook returns 400
→ no retry
→ notification FAILED
```

## No subscription

```text
event received
→ no active subscription
→ notification DISCARDED
```

## Retry exhaustion

```text
webhook repeatedly fails
→ retry schedule exhausted
→ notification FAILED
→ no DLQ entry
→ manual replay remains available
```

## Duplicate ingestion

```text
same event_id received multiple times
→ one logical notification
```

## Replay

```text
FAILED notification
→ authorized replay
→ 202 Accepted
→ new replay cycle
→ historical attempts preserved
```

## Cross-tenant access

```text
client A requests client B notification
→ 404
```

## Invalid cursor ownership

```text
client A cursor used by client B
→ request rejected
```

## Technical processing failure

```text
worker cannot safely process queue message
→ SQS redelivery
→ configured redrive threshold
→ DLQ
→ manual operational recovery
```

## SSRF

```text
private/internal destination
→ delivery rejected
```

## Authentication

```text
invalid issuer/signature/scope
→ request rejected
```

---

# 44. Definition of Done

The implementation is considered complete when:

* [x] consumer, worker, and API are independently deployable
* [x] shared domain/application abstractions are isolated from infrastructure
* [x] event ingestion is idempotent
* [x] notification state is durable
* [x] delivery attempts are persisted independently
* [x] subscription resolution supports the implemented matching rules
* [x] asynchronous delivery is implemented
* [x] retryable HTTP failures are retried
* [x] retry backoff and jitter are implemented
* [x] permanent failures transition to `FAILED`
* [x] no-subscription events transition to `DISCARDED`
* [x] retry exhaustion transitions to `FAILED`
* [x] retry exhaustion does not enter the DLQ
* [x] technical processing failures can reach the DLQ
* [x] DLQ recovery is manual
* [x] failed notifications can be replayed
* [x] replay preserves historical attempts
* [x] HMAC-SHA256 signing is implemented
* [x] secrets are stored outside DynamoDB
* [x] SSRF protections are implemented
* [x] redirects are restricted
* [x] tenant isolation is enforced
* [x] cursor pagination is tenant-safe
* [x] OIDC issuer and signature are validated
* [x] required scopes are enforced
* [x] rate limiting is implemented
* [x] sensitive values are excluded from logs
* [x] structured observability is available
* [x] local infrastructure is reproducible
* [x] architecture tests are present
* [x] integration tests are present
* [x] end-to-end scenarios are validated
* [x] coverage thresholds enforced by the build pass

---

# 45. Engineering Rationale

The central engineering principle is to assign each failure mode to the mechanism responsible for recovering from it.

```text
Kafka/event ingestion problem
        ↓
Kafka consumer semantics

Transient webhook failure
        ↓
SQS retry policy

Permanent webhook failure
        ↓
FAILED notification

Retry exhaustion
        ↓
FAILED notification

Failed notification
        ↓
Manual API replay

Worker processing failure
        ↓
SQS redelivery

Unrecoverable processing failure
        ↓
DLQ

DLQ message
        ↓
On-call investigation
        ↓
Manual recovery/redrive
```

This separation prevents customer delivery failures from being confused with infrastructure failures.

It also provides explicit recovery paths for both automated and human-operated failure scenarios.

The resulting system favors reliable delivery, observable state transitions, tenant isolation, and controlled operational recovery over ambiguous or implicit retry behavior.
