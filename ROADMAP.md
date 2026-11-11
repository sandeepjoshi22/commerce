# Commerce Lab — Roadmap

The map. `CARDS.md` is the daily execution list (Now / Parking note / Done); this file says **where each card fits and why it matters**.

Every module ends with: a working feature, lab notes, interview answers you can give without notes, and one system-design exercise.

---

## Timeline (estimates, ~2.5 h/day — slipping is fine, restarting is not)

| Module | What the system can do after it | Est. weeks | Target |
|---|---|---|---|
| M1 Inventory under concurrency | Never oversells, with measured trade-offs | 2 | ~Nov 22 |
| M2 Orders API & users | Authenticated users place orders from a cart, safely retried | 2 | ~Dec 6 |
| M3 Checkout & payments | Reserve → pay → confirm, survives slow/failed payments | 2 | ~Dec 20 |
| M4 Reliable events | Order events reach shipment reliably via outbox + Kafka | 3 | ~Jan 10 |
| M5 AI order-support assistant | Assistant answers order questions with tools, RAG and evals | 2 | ~Jan 24 |
| M6 Performance & observability | Measured, cached, traced, load-tested | 2 | ~Feb 7 |
| M7 Deploy & failure lab | Runs in containers/K8s; survives deliberate outages | 2 | ~Feb 21 |

---

## Coverage map — how this closes the baseline gaps

| Area (baseline) | M1 | M2 | M3 | M4 | M5 | M6 | M7 |
|---|---|---|---|---|---|---|---|
| Core Java (3) | ● concurrency | ○ records, BigDecimal | ○ enums/state | | ○ CompletableFuture | | ○ JVM in containers |
| Spring Boot (2) | ● @Transactional | ● security, lifecycle | ○ scheduling | ○ Spring Kafka | ● Spring AI | ○ Micrometer | ○ probes, shutdown |
| SQL/JPA (2) | ● locking, isolation | ● N+1, fetching | ○ tx boundaries | ○ outbox table | ○ pgvector | ● indexes, EXPLAIN | |
| Kafka/Backend (2) | | ○ idempotency | ● retries, timeouts | ● Kafka | | ○ caching | |
| Distributed systems (1) | ○ contention | | ● saga, unknown outcomes | ● delivery, ordering | | ○ capacity | ● failure |
| System design (2) | ○ flash sale | ○ idempotency svc | ● payments | ● notifications | ○ support agent | ● capacity | ○ incidents |
| DSA (2) | parallel track — see below |||||||
| Cloud (1) | | | | | | | ● |
| AI-native (1) | | | | | ● | | ○ AI failure drills |

● primary ○ secondary

---

## M1 — Inventory correctness under concurrency

**Outcome:** stock is always correct under heavy concurrency, and you can defend which approach you chose with numbers.

- [x] M1-01 Oversell with naive read-modify-write (lost updates)
- [ ] M1-02 Atomic conditional `UPDATE ... WHERE stock >= ?`
- [ ] M1-03 Optimistic locking: `@Version` + retry; what happens without retry
- [ ] M1-04 Pessimistic locking: `SELECT ... FOR UPDATE`; provoke a deadlock (two products, opposite order)
- [ ] M1-05 Isolation levels: `REPEATABLE READ` / `SERIALIZABLE` serialization failures
- [ ] M1-06 `@Transactional` traps: self-invocation, private methods, checked exceptions, `REQUIRES_NEW`
- [ ] M1-07 Compare all approaches under load; write the decision (ADR)

**You'll be able to answer:** What is a lost update? How do you prevent overselling? Optimistic vs pessimistic — when each? Why might `@Transactional` silently do nothing?
**Design exercise:** Flash sale — 50k users, 100 items. Where does the single-row DB approach break, and what replaces it?
**Exit check:** explain all approaches with your measured numbers, without notes.

## M2 — Orders API & users

**Outcome:** authenticated users build a cart and place an order; retries never double-order.

- [ ] M2-01 Users + Spring Security; trace the filter chain for one request
- [ ] M2-02 Product & cart CRUD — **generate it**, then review it against a checklist
- [ ] M2-03 `POST /orders` from cart: orders + order_items, price snapshot, `BigDecimal` totals
- [ ] M2-04 Validation + global errors (`@RestControllerAdvice`, `ProblemDetail`)
- [ ] M2-05 `Idempotency-Key`: unique constraint, concurrent double-submit, response replay
- [ ] M2-06 `GET /orders` with pagination; reproduce and fix N+1 (fetch join, entity graph, DTO projection)
- [ ] M2-07 Request lifecycle: filters vs interceptors vs AOP proxies — prove the order with logs

**You'll be able to answer:** How does Spring Security authenticate a request? What's N+1 and how do you find it? How do idempotency keys work? Filter vs interceptor?
**Design exercise:** LLD of the Order domain; HLD of an idempotency layer for a public payments API.
**Exit check:** walk through a request from socket to SQL and back.

## M3 — Checkout & payments

**Outcome:** reserve → pay → confirm works when payment is slow, fails, or times out with unknown result.

- [ ] M3-01 Separate order / payment / reservation states; transitions enforced in code, illegal ones tested
- [ ] M3-02 Reservations with expiry (scheduled job) — what if two instances run it?
- [ ] M3-03 Fake payment provider with configurable latency and failures
- [ ] M3-04 Hold a tx across the slow call → observe pool exhaustion; fix by splitting transactions
- [ ] M3-05 Timeouts, retries with backoff (Resilience4j); why retrying non-idempotent calls is dangerous
- [ ] M3-06 Compensation: payment fails → release reservation; what if the release fails?
- [ ] M3-07 Unknown outcome: timeout after the provider charged → reconciliation job

**You'll be able to answer:** How do you handle a payment timeout? What's a saga, and why not 2PC? How would you diagnose connection-pool exhaustion?
**Design exercise:** Design a payment system (HLD).
**Exit check:** for every failure point in checkout, say what state the system ends in and how it recovers.

## M4 — Reliable events

**Outcome:** order events reliably reach a shipment consumer; duplicates and failures are harmless.

- [ ] M4-01 Reproduce the dual-write problem (DB committed, event lost)
- [ ] M4-02 Transactional outbox + poller
- [ ] M4-03 Kafka in Docker Compose; topics, partitions, keys; publish `OrderPlaced` keyed by orderId
- [ ] M4-04 Shipment consumer; run 2 instances, observe partition assignment and rebalancing
- [ ] M4-05 Duplicates: kill consumer before offset commit; idempotent consumer (`processed_events`)
- [ ] M4-06 Ordering: key by the wrong field and show what breaks
- [ ] M4-07 Retries, dead-letter topic, poison messages
- [ ] M4-08 ADR: was Kafka justified here?

**You'll be able to answer:** Is exactly-once real? What ordering does Kafka guarantee? Outbox vs CDC? What is consumer lag and what causes it?
**Design exercise:** Design a notification system.
**Exit check:** kill any component mid-flow and predict correctly what happens.

## M5 — AI order-support assistant

**Outcome:** "Where is my order?" / "Cancel my last order" handled by an assistant that uses your APIs, is evaluated, and can't be tricked into acting for another user.

- [ ] M5-01 Spring AI setup; structured output into Java records
- [ ] M5-02 Tool calling: getOrders, getOrderStatus, cancelOrder — scoped to the current user
- [ ] M5-03 RAG over return/shipping policies with pgvector
- [ ] M5-04 Eval set (20–30 cases: expected answer/tool) run as a test
- [ ] M5-05 Guardrails: prompt injection via product description; confirmation before cancel
- [ ] M5-06 Log tokens, cost and latency per request; compare models
- [ ] M5-07 Failures: LLM timeout, malformed output, hallucinated order id

**You'll be able to answer:** How do you evaluate an LLM feature? Key RAG design decisions? How do you stop an agent taking unauthorized actions?
**Design exercise:** Customer-support agent at scale.
**Exit check:** eval suite passes, and you can show one injection attack it resists.

## M6 — Performance & observability

**Outcome:** you know the system's limits from measurement, not guesses.

- [ ] M6-01 Seed realistic volumes (products, orders)
- [ ] M6-02 Load test (k6) catalog + checkout; baseline p50/p95/p99
- [ ] M6-03 `EXPLAIN ANALYZE`, indexes, composite index column order
- [ ] M6-04 Redis cache-aside for catalog; invalidation; cache stampede
- [ ] M6-05 Metrics (Micrometer → Prometheus/Grafana) and tracing (OpenTelemetry) across Kafka
- [ ] M6-06 Back-of-envelope capacity estimate first, then compare with measured

**You'll be able to answer:** A query got slow — how do you debug it? Cache invalidation strategies? Why p99 matters more than average?
**Design exercise:** Capacity-plan the platform for 10x traffic.
**Exit check:** find and fix one real bottleneck using only dashboards and traces.

## M7 — Deploy & failure lab

**Outcome:** runs in containers on Kubernetes; you've broken it on purpose and written it up.

- [ ] M7-01 Multi-stage Dockerfile; JVM memory inside containers
- [ ] M7-02 Kubernetes locally (kind/k3s, or the spare machine): deployments, services, config
- [ ] M7-03 Probes, graceful shutdown, 2 replicas — revisit M3-02 (scheduled job running twice)
- [ ] M7-04 Map the deployment to AWS (VPC, subnets, ALB, RDS, MSK) as a design doc; deploy optionally
- [ ] M7-05 Failure drills: kill Postgres / Kafka / Redis, slow the DB, break retrieval — incident reports in Problem → Impact → Root Cause → Options → Recommendation format

**You'll be able to answer:** How does traffic reach a pod? What happens during a rolling deploy? Walk me through an incident you handled.
**Exit check:** three written incident reports.

---

## Parallel tracks (every week)

- **DSA — 3 problems/week.** Pattern order: arrays/hashing → two pointers → sliding window → stack → binary search → intervals → linked list → trees → graphs (BFS/DFS) → heaps → backtracking → DP (1D, then 2D). Roughly two patterns per module.
- **System design — 1 exercise per module** (listed above). Do it after the module so you design from experience.
- **Concept notes — Notion.** One note per new concept a card exposes.

## Rules

1. One module at a time. Finish its exit check; don't polish beyond it.
2. Bored by a card? Cut or generate it. Don't cut the module.
3. CRUD is generated; invariants, concurrency and failure handling are hand-written.
4. Missed days or weeks: resume at the parking note. Shift dates, never restart.
