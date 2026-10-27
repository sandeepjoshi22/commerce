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
