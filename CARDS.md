# Commerce Lab — Cards

Single source of truth for what's done and what's next. Lives in the repo, updated at the end of every session.

## Rules
- Card IDs follow `ROADMAP.md` (`M<module>-<nn>`). The roadmap says where a card fits; this file says what's active.
- One card in **Now** at a time.
- A card = question → prediction → experiment → result → lab note (`docs/labs/NN-name.md`).
- New technology only enters when a card needs it. No card, no Kafka.
- End every session by updating the **Parking note**, even after a 30-min Minimum day.
- Missed days: resume from the parking note. No catch-up.

## Now
- [ ] **M1-04 Pessimistic locking** — `SELECT ... FOR UPDATE` (`findByIdForUpdate`); results and experiment answers (reasoned, incl. deadlock) are in the lab note. No deadlock test by choice. → `docs/labs/02-fixing-oversell.md`

## Next — M1 Inventory correctness (see `ROADMAP.md`; order can change)
- [ ] **M1-02 Atomic conditional update** — `UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?`; check rows affected.
- [ ] **M1-05 Isolation levels** — `REPEATABLE READ` / `SERIALIZABLE` serialization failures.
- [ ] **M1-06 @Transactional traps** — self-invocation, private methods, checked-exception rollback, `REQUIRES_NEW`. Prove each with a test.
- [ ] **M1-07 Compare all approaches + ADR** — correctness, throughput, failure behaviour, deadlock risk, with your numbers. Then the M1 exit check and flash-sale design exercise.

## Later (one module at a time; cards are pulled from `ROADMAP.md` when the previous module's exit check passes)
- **M2** Orders API & users — Spring Security, cart, `POST /orders`, `Idempotency-Key`, N+1
- **M3** Checkout & payments — states, reservations + expiry, slow payment / pool exhaustion, compensation, unknown outcome
- **M4** Reliable events — dual write, outbox, Kafka, idempotent consumer, DLT
- **M5** AI order-support assistant — Spring AI, tools, RAG, evals, guardrails
- **M6** Performance & observability — k6, indexes, Redis cache, metrics/tracing
- **M7** Deploy & failure lab — Docker, K8s, AWS mapping, incident reports

## Done
- [x] **C00 Skeleton** — Gradle, Java 25, Boot 4, Flyway V1 product seed, Testcontainers smoke test. (2026-10-27)
- [x] **M1-01 Oversell** — naive read-modify-write under 100 concurrent requests, stock 10; 79–83 units oversold. → `docs/labs/01-oversell.md` (2026-11-10)
- [x] **M1-03 Optimistic locking** — `@Version` + retry; 0 oversold on all runs, lost sales without retry. → `docs/labs/02-fixing-oversell.md` step 1 (2026-11-10)

## Parking note
Next: wrap up M1-04. Pessimistic locking (`findByIdForUpdate`) works and lab 02 step 2 is written; the deadlock test was dropped to keep the code simple. Re-read the lab 02 experiment answers, then move M1-04 to Done and start M1-02 (atomic update). Write the prediction in the lab note BEFORE running.

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
