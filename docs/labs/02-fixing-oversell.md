# Lab 02: Fixing the oversell

Same setup as [Lab 01](01-oversell.md): product `id = 1`, starting `stock = 10`, threads released together by a latch, each ordering 1 unit. Stock is reset to 10 before every run.

Pass condition for every fix: `successes + finalStock == 10` on every run.

**Baseline (no protection):** successes 91–92, finalStock 0, **oversold 81–82**, about 190 ms.

> Run with `./gradlew test --tests OversellTest --rerun`. Without `--rerun`, Gradle reuses the last result when no code changed, and every "run" prints identical numbers.

## Step 1: Optimistic locking (`@Version`)

### Change

- `@Version private long version;` on `Product`. Hibernate now writes
  `UPDATE product SET stock = ?, version = version + 1 WHERE id = ? AND version = ?`.
  If another transaction committed first, the version no longer matches, 0 rows are updated, and Spring throws `ObjectOptimisticLockingFailureException`. The transaction rolls back.
- `RetryingOrderPlacer` calls `InventoryService.placeOrder` again on that exception, up to `maxAttempts` times, with 1–20 ms of random back-off. It must be a separate bean: a retry inside `InventoryService` would call itself without going through the Spring proxy, so it would not get a fresh transaction.

### Results (3 rounds each)

| Threads | Max attempts | successes | finalStock | oversold | Lock conflicts (gave up) | Out of stock | Attempts used by winners | Time |
|---|---|---|---|---|---|---|---|---|
| 100 | 1 | 10, 10, 10 | 0 | 0 | 66, 75, 80 | 24, 15, 10 | 10 | ~215 ms |
| 10 | 1 | 1, 2, 1 | 9, 8, 9 | 0 | 9, 8, 9 | 0 | 1–2 | ~12 ms |
| 10 | 3 | 8, 8, 8 | 2 | 0 | 2, 2, 2 | 0 | 18–19 | ~45 ms |
| 10 | 10 | 10, 10, 10 | 0 | 0 | 0 | 0 | 22–25 | ~60 ms |

### What happened

**The oversell is gone in every configuration.** `oversold = 0` on all 12 runs. A stale read can no longer turn into a write, because the version check makes the database reject it.

**100 threads, no retry: looks perfect, but most rejections are for the wrong reason.** All 10 units sold. Of the 90 rejected customers, 66–80 were told "conflict, try again", not "out of stock", many of them while stock was still available. It sold out only because there were 10 times more buyers than units, and later waves picked up what earlier losers left.

**10 threads, no retry: the real cost shows.** 10 buyers, 10 units, and only 1 or 2 sales. All 10 read `version = 0`, one commits, and the other 9 fail. Stock is left on the shelf while customers are turned away. No oversell, but also lost sales.

**Retries recover the sales.** With 3 attempts, 8 of 10 sold. With 10 attempts, all 10 sold. The winners needed 22–25 attempts in total for 10 sales, so roughly 1 in 2 transactions was wasted work: it read the row, did the check, tried to commit, and rolled back.

**Retries cost time.** 12 ms without retries, about 60 ms with up to 10, mostly back-off sleeps and repeated transactions.

### Lessons

1. **Optimistic locking detects the conflict; it does not resolve it.** Without a retry, `@Version` swaps overselling for refusing valid orders.
2. **"Conflict" is not "out of stock".** Count the reasons separately, otherwise the first table row looks like a clean pass.
3. **Retries need a limit and jitter.** Without jitter, the losers collide again on the next attempt. Without a limit, a hot product could make requests retry for a long time.
4. **The retry must wrap the transaction, not run inside it.** Each attempt needs a fresh transaction and a fresh read of the row.
5. **Good fit when conflicts are rare** (many products, few buyers each). Under heavy contention on one row, about half the work is thrown away. Step 2 (pessimistic locking) is the alternative for that case.

## Step 2: Pessimistic locking (`SELECT ... FOR UPDATE`)

### Change

- `ProductRepository.findByIdForUpdate` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`. On PostgreSQL, Hibernate generates:
  ```sql
  select ... from product p1_0 where p1_0.id=? for no key update of p
  ```
  `FOR NO KEY UPDATE` is PostgreSQL's slightly weaker form of `FOR UPDATE`: it blocks other writers and other lockers of the row, but not inserts into tables with a foreign key to it. Since we never change the primary key, it gives the same protection here.
- `InventoryService.placeOrderWithLock` is `placeOrder` using that finder. No retry wrapper.
- `@Version` stays on `Product`. It never fires here, because only one transaction at a time can hold the row.

### Results (3 rounds, all 7 cases in one test run)

| Strategy | Threads | Max attempts | successes | finalStock | Lock conflicts | Out of stock | Attempts used by winners | Time |
|---|---|---|---|---|---|---|---|---|
| optimistic | 100 | 1 | 10 | 0 | 61–72 | 18–29 | 10 | 172–206 ms* |
| optimistic | 10 | 1 | 1 | 9 | 9 | 0 | 1 | 10–14 ms |
| optimistic | 10 | 3 | 7–9 | 1–3 | 1–3 | 0 | 14–18 | 39–50 ms |
| optimistic | 10 | 10 | 10 | 0 | 0 | 0 | 24–26 | 44–66 ms |
| optimistic | 100 | 10 | 10 | 0 | 0 | 90 | 10–12 | 64–81 ms |
| **pessimistic** | 10 | – | **10** | 0 | 0 | 0 | **10** | 61–72 ms |
| **pessimistic** | 100 | – | **10** | 0 | 0 | **90** | **10** | 91–107 ms |

\* This case always runs first, so its time includes JVM and connection-pool warm-up. Do not compare it directly with the others.

### What happened

**Pessimistic locking gives the ideal outcome with no retry code.** Every run: exactly 10 sold, and every other customer told "out of stock", which is the true reason. No conflicts, no wasted attempts.

**Why:** the first transaction to run `FOR UPDATE` gets the row lock. Everyone else's `SELECT` blocks until it commits, then reads the *new* stock. The read-modify-write gap from Lab 01 still exists, but no other transaction can get into it.

**The cost is waiting instead of retrying.** 10 buyers took about 65 ms because their transactions ran one after another. Optimistic with no retry took about 12 ms, but sold only 1 unit. Optimistic with 10 retries took a similar 45–65 ms for the same 10 sales, with 2.5 times as many transactions.

**At this scale the times are close.** The two approaches only separate clearly under conditions this test does not create: long transactions, many products, or slow networks. Do not draw strong performance conclusions from tens of milliseconds.

### Lessons

1. **Pessimistic locking prevents the conflict instead of detecting it.** Correct results and honest failure reasons, without retry logic.
2. **Waiting is the price.** Every request for the same product is processed one at a time. A slow transaction holding the lock, for example one calling a payment API, would make everyone behind it wait.
3. **It ties up connections while waiting.** A blocked transaction holds a database connection the whole time. See experiment 2 below.
4. **Choose by contention.** Rare conflicts (most products): optimistic is cheaper. Frequent conflicts on a hot row (a flash sale): pessimistic avoids the retry storm.

### Experiments (answered by reasoning, not measured)

These were not run, to keep the codebase simple. The answers come from how the locks work; treat the numbers as estimates.

1. **Make the transaction slow (`sleep(50)` after the stock check).**
   - *Pessimistic, 10 threads:* about 500 ms. The sleep happens while holding the row lock, so the 10 transactions run one after another (10 × 50 ms).
   - *Optimistic, 10 attempts:* also about 500 ms, because only one thread wins per round (10 rounds of ~50 ms).
   - *Which degrades worse:* optimistic. The wall time is similar, but it does about 55 attempts (10 + 9 + ... + 1) for 10 sales, and the unluckiest threads may run out of attempts. Pessimistic does exactly 10 and is predictable, but all of it is queued behind one lock.
2. **Pool of 2 with pessimistic locking.**
   - *Result:* still correct, 10 sold. *Time:* a bit higher.
   - *Who waits for what:* one transaction holds the lock and a connection. The second holds a connection and waits for the lock. The other threads wait for a free connection from Hikari, not for the lock.
   - *Risk:* with slow transactions, the connection wait can hit Hikari's 30 s `connectionTimeout`. There is no deadlock, because the lock holder already has its connection.
3. **Fail fast (`lock.timeout = 0`).**
   - *SQL:* Hibernate adds `nowait`, giving `... for no key update of p nowait`.
   - *Loser exception:* a `PessimisticLockingFailureException` (SQLSTATE `55P03`, lock not available).
   - *Compared with optimistic without retries:* the same shape of failure. Few sales, many "try again" errors while stock is still available. It is cheaper, because the loser fails before doing any work instead of rolling back at commit. A retry would be needed to recover the sales.
4. **Deadlock (A locks 1 then 2, B locks 2 then 1).**
   - *PostgreSQL:* each waits for the other. After `deadlock_timeout` (1 s by default) Postgres detects the cycle and aborts one transaction with SQLSTATE `40P01` (`deadlock detected`). The other continues.
   - *Prevention:* always lock rows in the same order, for example sort the product ids ascending before locking, or lock them in one query with `ORDER BY id FOR UPDATE`. Retrying the aborted transaction is the fallback.
