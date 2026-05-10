package com.isakatirci.MVP;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-concurrency bidirectional stress test for the Ledger Service.
 * Performs 25,000 parallel transactions between two accounts.
 */
public class BidirectionalTransferStressTest {
    private static final String BASE_URL = "http://localhost:8080/api/v1";
    private static final String TRANSFER_URL = BASE_URL + "/transfer";
    private static final String ACCOUNTS_URL = BASE_URL + "/accounts";
    private static final String BALANCE_URL = BASE_URL + "/accounts/%s/balance";
    private static final String RESET_URL = BASE_URL + "/debug/reset";

    public static void main(String[] args) throws Exception {
        int transactionCountPerPair = 25000;
        int totalRequests = transactionCountPerPair * 2; // 25,000 requests
        double amount = 5.0;
        double initialBalance = 7000.0;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // 0. RESET: Clear previous state
        System.out.printf("--- RESET: Truncating tables ---%n");
        resetDatabase(client);

        // 1. SETUP: Ensure accounts exist and have the correct starting balance
        System.out.printf("--- SETUP: Preparing accounts ---%n");
        setupAccount(client, "ACC001", initialBalance);
        setupAccount(client, "ACC002", initialBalance);
        System.out.printf("Accounts ready.%n%n");

        // 2. PREPARE REQUESTS
        List<TestRequest> requests = new ArrayList<>();
        for (int i = 0; i < transactionCountPerPair; i++) {
            requests.add(new TestRequest("stress-1to2-" + UUID.randomUUID(),
                    createPayload("ACC001", "ACC002", amount), "ACC001 -> ACC002", amount));
            requests.add(new TestRequest("stress-2to1-" + UUID.randomUUID(),
                    createPayload("ACC002", "ACC001", amount), "ACC002 -> ACC001", amount));
        }
        Collections.shuffle(requests);

        System.out.printf("Total requests to send: %d%n", requests.size());

        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 3. EXECUTION: Virtual Threads
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TestRequest testReq : requests) {
                executor.submit(() -> {
                    String threadId = Thread.currentThread().toString();
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(TRANSFER_URL))
                                .header("Content-Type", "application/json")
                                .header("Idempotency-Key", testReq.idempotencyKey)
                                .POST(HttpRequest.BodyPublishers.ofString(testReq.payload))
                                .timeout(Duration.ofSeconds(60))
                                .build();

                        // System.out.printf("[READY] Thread: %s, Flow: %s, Amount: %.2f%n", threadId, testReq.flow, testReq.amount);
                        readyLatch.countDown();
                        startLatch.await(); // Sync point for all threads

                        long reqStart = System.currentTimeMillis();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long reqEnd = System.currentTimeMillis();

                        if (response.statusCode() == 201) {
                            successCount.incrementAndGet();
                            System.out.printf("[SUCCESS] Flow: %s, Amount: %.2f, Status: %d, Time: %d ms%n", 
                                    testReq.flow, testReq.amount, response.statusCode(), (reqEnd - reqStart));
                        } else {
                            failCount.incrementAndGet();
                            System.out.printf("[FAILED] Flow: %s, Amount: %.2f, Status: %d, Body: %s%n", 
                                    testReq.flow, testReq.amount, response.statusCode(), response.body());
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        System.out.printf("[ERROR] Flow: %s, Amount: %.2f, Message: %s%n", 
                                testReq.flow, testReq.amount, e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            System.out.printf("Waiting for %d virtual threads to be ready...%n", totalRequests);
            readyLatch.await();
            System.out.printf("🚀 Firing all requests simultaneously...%n");

            long startTime = System.currentTimeMillis();
            startLatch.countDown();
            doneLatch.await();
            long endTime = System.currentTimeMillis();

            System.out.printf("%n--- RESULTS ---%n");
            System.out.printf("Duration: %d ms%n", (endTime - startTime));
            System.out.printf("Throughput: %.2f req/sec%n", (totalRequests / ((endTime - startTime) / 1000.0)));
            System.out.printf("Successful: %d%n", successCount.get());
            System.out.printf("Failed: %d%n", failCount.get());
            System.out.printf("----------------%n%n");

            // 4. VERIFICATION
            System.out.printf("Verifying final balances...%n");
            // Since 5000 transactions went from 1 to 2, and 5000 went from 2 to 1 with the same amount, balances should be unchanged.
            verifyBalance(client, "ACC001", 7000.0); 
            verifyBalance(client, "ACC002", 7000.0); 
        }
    }

    private static void setupAccount(HttpClient client, String accountId, double balance) throws Exception {
        String payload = String.format("{\"accountId\": \"%s\", \"initialBalance\": %f}", accountId, balance);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ACCOUNTS_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201) {
            System.out.printf("Created account: %s%n", accountId);
        } else {
            System.out.printf("Failed to create account %s. Status: %d, Body: %s%n", 
                    accountId, response.statusCode(), response.body());
            throw new RuntimeException("Account setup failed");
        }
    }

    private static void resetDatabase(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RESET_URL))
                .DELETE()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) {
            System.out.printf("Database reset successfully.%n");
        } else {
            System.out.printf("Database reset failed. Status: %d%n", response.statusCode());
            throw new RuntimeException("Database reset failed");
        }
    }

    private static String createPayload(String from, String to, double amount) {
        return String.format("""
                {
                  "fromAccountId": "%s",
                  "toAccountId": "%s",
                  "amount": %.2f,
                  "valueDate": "2026-05-10",
                  "metadata": "bidirectional-stress-test"
                }
                """, from, to, amount);
    }

    private static void verifyBalance(HttpClient client, String accountId, double expected) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(BALANCE_URL, accountId)))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            boolean match = body.contains("\"balance\":" + expected) || body.contains("\"balance\":" + (int) expected);
            System.out.printf("%s: %s (expected: %.2f) %s%n",
                    accountId, body, expected, match ? "✅" : "❌ MISMATCH");
        } catch (Exception e) {
            System.out.printf("%s: ERROR - %s ❌%n", accountId, e.getMessage());
        }
    }

    record TestRequest(String idempotencyKey, String payload, String flow, double amount) {
    }
}
