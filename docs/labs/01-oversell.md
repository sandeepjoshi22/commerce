# Lab 01: Overselling under concurrency

## Goal

See what happens when many customers buy the same product at the same moment, while the code has no concurrency protection.

## Setup

| Piece | Detail |
|---|---|
| Product | `id = 1`, starting `stock = 10` (seeded by Flyway V1) |
| Code under test | `InventoryService.placeOrder(productId, qty)`: load product, check stock, set `stock = stock - qty`, rely on JPA dirty checking to write it back |
| Protection | None. `@Version` removed from `Product`, no locks, default `READ COMMITTED` isolation |
| Test | `OversellTest`: 100 threads wait on a `CountDownLatch`, are released together, and each orders 1 unit |
| Database | PostgreSQL 17 in Testcontainers, Hikari pool of 10 connections by default |

### The invariant that matters

```
units sold + units left = starting stock
successes  + finalStock = 10
```

Any result where `successes + finalStock > 10` means stock was sold that never existed. The difference is the number of units oversold. This check works for any number of threads, unlike asserting `successes == 10`.

## Prediction

- **successes**: unpredictable, because threads race on the same row.
- **finalStock**: unpredictable, but never negative because of the `CHECK (stock >= 0)` constraint.

## Baseline result

| Run | successes | failures | finalStock | Oversold |
|---|---|---|---|---|
| 1 | 89 | 11 | 0 | **79** |
| 2 | 93 | 7 | 0 | **83** |

`finalStock = 0` looks correct. It is not: 89 customers were told their order succeeded for 10 units of stock.

### Why

`placeOrder` is a **read-modify-write** done in Java:

```
T1: SELECT stock → 10
T2: SELECT stock → 10
T1: UPDATE stock = 9   (commit)
T2: UPDATE stock = 9   (commit, overwrites T1)
```

Both transactions sold a unit; the stock dropped by one. This is a **lost update**. Under `READ COMMITTED`, PostgreSQL makes T2 wait for T1's row lock, then lets T2's write go through, because T2 is writing a plain value and the database has no idea it was computed from a stale read.

The 7 to 11 failures are the threads that happened to read `stock = 0` and threw "Out of stock".

### About the prediction

The prediction that stock never goes negative was right, but for a different reason. A thread only writes `stock - 1` after reading `stock >= 1`, so every write is `>= 0`. The `CHECK` constraint never fired. It protects the column from bad values, not the business from bad sales.

## Experiment 1: remove `start.await()`

Threads now start as soon as they are submitted, with no synchronized release.

| Run | successes | failures | finalStock | Oversold |
|---|---|---|---|---|
| 1 | 93 | 7 | 0 | 83 |
| 2 | 94 | 6 | 2 | **92** |
| 3 | 86 | 14 | 3 | 79 |

**Observation:** the oversell is just as bad, and `finalStock` is now sometimes above zero after 94 sales.

**Why:** the latch was never the cause. 100 threads queue for 10 connections and their transactions overlap anyway. The final stock is simply whatever the last transaction to commit wrote, and that transaction may have read an old value like 3.

**Lesson:** a race condition does not need perfect timing. Any overlap between read and write is enough.

## Experiment 2: `THREADS = 2`

| Run | successes | failures | finalStock | Oversold |
|---|---|---|---|---|
| 1 | 2 | 0 | 9 | 1 |
| 2 | 2 | 0 | 9 | 1 |
| 3 | 2 | 0 | 9 | 1 |

**Observation:** two units sold, stock went from 10 to 9. Correct would be 8. The update was lost on every run.

**Why it was consistent:** the latch lined up both threads exactly, so both always read 10 before either wrote.

**What this means in production:** real traffic has no latch. Two requests for the same product rarely land within the same few milliseconds, so this bug appears only sometimes, under load, on popular items. It passes unit tests, manual QA, and most of staging, then shows up during a sale.

**Lesson:** "it works when I test it" says nothing about concurrency bugs. Low traffic hides them; it does not remove them.

## Experiment 3: remove `@Transactional` from `placeOrder`

| Result (every run) | successes = 100 | failures = 0 | finalStock = 10 |
|---|---|---|---|

**Observation:** 100 orders accepted, stock unchanged. Nothing was ever written.

**Why:** JPA writes changes through **dirty checking**. When a transaction commits, Hibernate compares each *managed* entity with its original snapshot and issues an `UPDATE` for anything that changed.

Without `@Transactional` on `placeOrder`:

1. `findById` runs in its own short read-only transaction (Spring Data's default).
2. That transaction ends, the persistence context closes, and the returned `Product` is **detached**.
3. `updateStock()` changes a plain Java object that nothing is tracking.
4. No flush, no `UPDATE`.

So every thread reads 10, passes the check, and "succeeds".

Adding `productRepository.save(product)` would make it write again, but the lost update from the baseline would come straight back, because the read and the write would still be separate steps.

**Lesson:** dirty checking only applies to managed entities inside an open transaction. Without it, an entity's setters do nothing to the database, silently.

## Experiment 4: `maximum-pool-size = 2`

### First attempt: setting had no effect

The test config was written as:

```yaml
# src/test/resources/application.yml
spring.datasource.hikari.maximum-pool-size=2
```

That is `.properties` syntax in a YAML file. YAML does not read it as a key, so Hikari kept its default of 10. The results (90 and 80 successes) were just repeats of the baseline. Hikari's debug log confirmed `maximumPoolSize = 10`.

### Second attempt: valid YAML

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 2
```

| Run | successes | failures | finalStock | Oversold |
|---|---|---|---|---|
| 1 | 20 | 80 | 0 | **10** |
| 2 | 20 | 80 | 0 | **10** |

**Observation:** the oversell dropped from about 80 to exactly 10, and became perfectly repeatable.

**Why:** with only 2 connections, transactions run in pairs. Both members of a pair read the same stock and both write `stock - 1`. Ten pairs sell 20 units and reduce stock by 10.

**Lesson:** limiting concurrency changes *how much* you oversell, not *whether* you oversell. Two overlapping transactions are enough. A smaller pool is a throughput setting, not a correctness tool.

**Side lesson:** always confirm a config change actually applied before drawing conclusions from it. Also, a `src/test/resources/application.yml` *replaces* the one in `src/main/resources` during tests, so settings like `ddl-auto: validate` are lost unless copied over.

## Summary of lessons

1. **Read-modify-write in application code is not safe under concurrency.** Reading a value, computing a new one in Java, and writing it back loses updates whenever two transactions overlap.
2. **Look at the right number.** Stock looked correct (0) while the business had sold 8 to 9 times its inventory. Check `sold + remaining = initial`, not just the remaining stock.
3. **Database constraints guard values, not logic.** `CHECK (stock >= 0)` never fired, because each write was valid on its own.
4. **Race conditions are probabilistic.** They depend on load and timing, so they hide in testing and appear in production. A test has to force overlap (the latch) to make them reproducible.
5. **`@Transactional` is what makes dirty checking work.** Remove it and entity changes are silently discarded.
6. **Reducing concurrency is not a fix.** Even 2 parallel transactions oversell.
7. **Verify your experiment setup.** A config that silently does nothing produces confident, wrong conclusions.

## Next: fixes to try

Each one should make `successes + finalStock == 10` hold on every run.

| Approach | How | Trade-off |
|---|---|---|
| Optimistic locking | Restore `@Version` on `Product`; retry on `ObjectOptimisticLockingFailureException` | No locks held, but many retries under heavy contention |
| Pessimistic locking | `@Lock(PESSIMISTIC_WRITE)` on a repository finder (`SELECT ... FOR UPDATE`) | Always correct, but requests on the same product run one at a time |
| Atomic conditional update | `UPDATE product SET stock = stock - :qty WHERE id = :id AND stock >= :qty`, check rows updated | One statement, no read-modify-write gap; bypasses entity logic |
