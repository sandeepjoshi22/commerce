# Commerce Lab — Cards

Single source of truth for what's done and what's next. Lives in the repo, updated at the end of every session.

## Rules
- One card in **Now** at a time.
- A card = question → prediction → experiment → result → lab note (`docs/labs/NN-name.md`).
- New technology only enters when a card needs it. No card, no Kafka.
- End every session by updating the **Parking note**, even after a 30-min Minimum day.
- Missed days: resume from the parking note. No catch-up.

## Now
- [ ] **C01 Oversell** — naive `placeOrder` under 100 concurrent requests, stock 10. Prediction first, then watch it fail. → `docs/labs/01-oversell.md`

## Next (order can change; pick what's interesting)
- [ ] **C02 Fix A: atomic update** — `UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?`; check rows affected.
- [ ] **C03 Fix B: optimistic locking** — `@Version` + retry. What happens without retry? How many retries under contention?
- [ ] **C04 Fix C: pessimistic locking** — `SELECT ... FOR UPDATE`. Compare A/B/C: correctness, throughput, failure behaviour, deadlock risk.
- [ ] **C05 @Transactional traps** — self-invocation, private methods, checked-exception rollback. Prove each with a test.
- [ ] **C06 Idempotent POST /orders** — `Idempotency-Key` header, unique constraint, concurrent double-submit test. No check-then-insert.
- [ ] **C07 Orders + reservations** — `orders`, `order_items`, `inventory_reservations`; separate order / inventory states.
- [ ] **C08 Slow payment** — fake provider with latency. Hold the tx across the call and observe pool exhaustion; then fix.
- [ ] **C09 Compensation** — payment fails → reservation released. What if the release itself fails?
- [ ] **C10 Dual write** — order committed, event lost. Reproduce, then fix with a transactional outbox.

## Later (pulled in only by a card)
- Kafka + idempotent consumer (`processed_events`)
- Redis as cache (never system of record)
- AI thread: order-support assistant, tool calling against own APIs, structured output, small eval set, cost/latency per request
- Auth / Spring Security
- Observability (metrics, tracing)
- Docker image, K8s, AWS

## Done
- [x] **C00 Skeleton** — Gradle, Java 25, Boot 4, Flyway V1 product seed, Testcontainers smoke test. (2026-10-27)

## Parking note
Next: write the C01 concurrency test (ExecutorService + CountDownLatch). Write the prediction in the lab note BEFORE running.

## DSA log (minimum ~3/week)
| Week of | Problems | Pattern |
|---|---|---|
| 2026-10-26 | 0 | |

---

## Lab note template (`docs/labs/NN-name.md`)
```
# NN — Title
## Question
## Prediction (written before running)
## Experiment
## Result (numbers)
## Why
## Options & trade-offs
## Decision
## Interview version (3–4 sentences)
```
