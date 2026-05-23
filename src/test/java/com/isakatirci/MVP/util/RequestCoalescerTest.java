package com.isakatirci.MVP.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RequestCoalescerTest {

    private final RequestCoalescer coalescer = new RequestCoalescer();

    @Test
    @DisplayName("Should coalesce concurrent requests for the same key and invoke loader only once")
    void shouldCoalesceConcurrentRequests() throws Exception {
        AtomicInteger loaderCalls = new AtomicInteger(0);
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    latch.await(); // Synchronize all starts
                    return coalescer.coalesce("key1", () -> {
                        loaderCalls.incrementAndGet();
                        try {
                            Thread.sleep(100); // Simulate slow loading
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "result1";
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor));
        }

        // Release all threads
        latch.countDown();

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // All should get the correct result
        for (CompletableFuture<String> future : futures) {
            assertEquals("result1", future.get());
        }

        // Loader should only be called once
        assertEquals(1, loaderCalls.get());

        executor.shutdown();
    }

    @Test
    @DisplayName("Should not coalesce requests for different keys")
    void shouldNotCoalesceDifferentKeys() throws Exception {
        AtomicInteger loaderCalls = new AtomicInteger(0);
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    latch.await();
                    return coalescer.coalesce("key" + index, () -> {
                        loaderCalls.incrementAndGet();
                        return "result" + index;
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor));
        }

        latch.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (int i = 0; i < threadCount; i++) {
            assertEquals("result" + i, futures.get(i).get());
        }

        // Loader should be called 3 times (once per key)
        assertEquals(3, loaderCalls.get());

        executor.shutdown();
    }

    @Test
    @DisplayName("Should propagate exception to all coalesced waiting threads if loader fails")
    void shouldPropagateExceptions() throws Exception {
        AtomicInteger loaderCalls = new AtomicInteger(0);
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    latch.await();
                    return coalescer.coalesce("errorKey", () -> {
                        loaderCalls.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new RuntimeException("DB error");
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor));
        }

        latch.countDown();

        for (CompletableFuture<String> future : futures) {
            ExecutionException exception = assertThrows(ExecutionException.class, future::get);
            Throwable cause = exception.getCause(); // The RuntimeException thrown from lambda
            assertTrue(cause.getMessage().contains("DB error"));
        }

        assertEquals(1, loaderCalls.get());
        executor.shutdown();
    }

    @Test
    @DisplayName("Should allow subsequent requests after the first request has completed")
    void shouldAllowFutureRequestsAfterFirstCompletes() {
        AtomicInteger loaderCalls = new AtomicInteger(0);

        String firstResult = coalescer.coalesce("key1", () -> {
            loaderCalls.incrementAndGet();
            return "result1";
        });

        assertEquals("result1", firstResult);
        assertEquals(1, loaderCalls.get());

        // Subsequent request for same key after removal
        String secondResult = coalescer.coalesce("key1", () -> {
            loaderCalls.incrementAndGet();
            return "result2";
        });

        assertEquals("result2", secondResult);
        assertEquals(2, loaderCalls.get());
    }
}
