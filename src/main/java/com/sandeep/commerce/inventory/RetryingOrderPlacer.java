package com.sandeep.commerce.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries {@link InventoryService#placeOrder} when another transaction updated the product first.
 * Must live outside InventoryService: each attempt needs a fresh transaction, which only happens
 * when the call goes through the Spring proxy.
 */
@Component
public class RetryingOrderPlacer {

    private final InventoryService inventoryService;
    private final int maxAttempts;

    public RetryingOrderPlacer(InventoryService inventoryService,
                               @Value("${inventory.order.max-attempts:3}") int maxAttempts) {
        this.inventoryService = inventoryService;
        this.maxAttempts = maxAttempts;
    }

    /** Returns the number of attempts used. */
    public int placeOrder(Long productId, int qty) {
        for (int attempt = 1; ; attempt++) {
            try {
                inventoryService.placeOrder(productId, qty);
                return attempt;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                backOff();
            }
        }
    }

    private static void backOff() {
        try {
            // random jitter so the losers don't all collide again on the next attempt
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying", e);
        }
    }
}
