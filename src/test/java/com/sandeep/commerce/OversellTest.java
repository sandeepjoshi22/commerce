package com.sandeep.commerce;

import com.sandeep.commerce.inventory.InventoryService;
import com.sandeep.commerce.inventory.Product;
import com.sandeep.commerce.inventory.ProductRepository;
import com.sandeep.commerce.inventory.RetryingOrderPlacer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OversellTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    InventoryService inventoryService;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private static final long PRODUCT_ID = 1L;
    private static final int INITIAL_STOCK = 10;

    @BeforeEach
    void resetStock() {
        jdbcTemplate.update("UPDATE product SET stock = ? WHERE id = ?", INITIAL_STOCK, PRODUCT_ID);
    }

    @ParameterizedTest(name = "threads={0} maxAttempts={1}")
    @CsvSource({
            "100, 1",   // demand far above stock, no retry
            "10, 1",    // demand equals stock, no retry
            "10, 3",
            "10, 10",
    })
    void doesNotOversellUnderConcurrency(int threads, int maxAttempts) throws Exception {
        RetryingOrderPlacer orderPlacer = new RetryingOrderPlacer(inventoryService, maxAttempts);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        Map<String, LongAdder> failures = new ConcurrentHashMap<>();

        long startedAt = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    attempts.addAndGet(orderPlacer.placeOrder(PRODUCT_ID, 1));
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.computeIfAbsent(e.getClass().getSimpleName(), k -> new LongAdder()).increment();
                }
                return null;
            });
        }

        ready.await();          // wait until every thread is lined up
        start.countDown();
        pool.shutdown();

        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "tasks didn't finish");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Product product = productRepository.findById(PRODUCT_ID).orElseThrow();
        int sold = successes.get();
        int finalStock = product.getStock();
        int oversold = sold + finalStock - INITIAL_STOCK;

        System.out.printf("threads=%d maxAttempts=%d successes=%d finalStock=%d oversold=%d "
                        + "attemptsBySuccesses=%d elapsedMs=%d failures=%s%n",
                threads, maxAttempts, sold, finalStock, oversold,
                attempts.get(), elapsedMs, new TreeMap<>(failures));

        assertTrue(finalStock >= 0, "stock went negative: " + finalStock);
        assertEquals(INITIAL_STOCK, sold + finalStock,
                "sold + finalStock must equal initial stock; oversold by " + oversold);
    }
}
