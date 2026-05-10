package com.isakatirci.MVP.service;

import com.isakatirci.MVP.dto.CreateTransferRequest;
import com.isakatirci.MVP.repository.AccountRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class StressTestService {

    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;

    private StressTestStatus currentStatus = StressTestStatus.builder()
            .status("IDLE")
            .build();

    @Data
    @Builder
    public static class StressTestStatus {
        private String status;
        private int totalRequests;
        private int successful;
        private int failed;
        private long durationMs;
        private double tps;
        private LocalDateTime startTime;
        private String message;
        @Builder.Default
        private List<String> errorMessages = new java.util.ArrayList<>();
    }

    public StressTestStatus getStatus() {
        if ("RUNNING".equals(currentStatus.getStatus())) {
            long duration = Duration.between(currentStatus.getStartTime(), LocalDateTime.now()).toMillis();
            currentStatus.setDurationMs(duration);
            if (duration > 0) {
                currentStatus.setTps((currentStatus.getSuccessful() + currentStatus.getFailed()) / (duration / 1000.0));
            }
        }
        return currentStatus;
    }

    public void runStressTest(int totalRequests, Consumer<StressTestStatus> progressListener) {
        if ("RUNNING".equals(currentStatus.getStatus())) {
            throw new IllegalStateException("A stress test is already running");
        }

        currentStatus = StressTestStatus.builder()
                .status("RUNNING")
                .totalRequests(totalRequests)
                .successful(0)
                .failed(0)
                .startTime(LocalDateTime.now())
                .message("Initializing test...")
                .build();

        CompletableFuture.runAsync(() -> {
            try {
                executeTest(totalRequests, progressListener);
            } catch (Exception e) {
                log.error("Stress test failed", e);
                currentStatus.setStatus("FAILED");
                currentStatus.setMessage(e.getMessage());
                if (progressListener != null) progressListener.accept(currentStatus);
            }
        });
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private void executeTest(int totalRequests, Consumer<StressTestStatus> progressListener) throws Exception {
        int transactionCountPerPair = totalRequests / 2;
        BigDecimal amount = new BigDecimal("5.00");
        BigDecimal initialBalance = new BigDecimal("100000.00");

        // 1. SETUP: Ensure accounts exist (Local call is fine for setup)
        currentStatus.setMessage("Preparing accounts...");
        if (progressListener != null) progressListener.accept(currentStatus);
        
        setupAccount("ACC001", initialBalance);
        setupAccount("ACC002", initialBalance);

        // 2. PREPARE REQUESTS
        currentStatus.setMessage("Preparing " + totalRequests + " requests...");
        if (progressListener != null) progressListener.accept(currentStatus);

        List<TestRequest> requests = new ArrayList<>();
        for (int i = 0; i < transactionCountPerPair; i++) {
            requests.add(new TestRequest(UUID.randomUUID().toString(), "ACC001", "ACC002", amount));
            requests.add(new TestRequest(UUID.randomUUID().toString(), "ACC002", "ACC001", amount));
        }
        Collections.shuffle(requests);

        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        String baseUrl = "http://ledger-lb/api/v1/transfer"; // Internal network URL

        currentStatus.setMessage("Launching " + totalRequests + " virtual threads...");
        if (progressListener != null) progressListener.accept(currentStatus);

        // 3. EXECUTION: Virtual Threads hitting the Load Balancer
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TestRequest testReq : requests) {
                executor.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await(); 

                        String payload = String.format(
                                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s,\"valueDate\":\"2026-05-10\",\"metadata\":\"stress-test-ui\"}",
                                testReq.from, testReq.to, testReq.amount
                        );

                        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(baseUrl))
                                .header("Content-Type", "application/json")
                                .header("Idempotency-Key", testReq.idempotencyKey)
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                                .build();

                        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 201) {
                            int s = successCount.incrementAndGet();
                            currentStatus.setSuccessful(s);
                        } else {
                            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
                        }
                    } catch (Exception e) {
                        int f = failCount.incrementAndGet();
                        currentStatus.setFailed(f);
                        String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        synchronized (currentStatus.getErrorMessages()) {
                            if (currentStatus.getErrorMessages().size() < 10 && !currentStatus.getErrorMessages().contains(error)) {
                                currentStatus.getErrorMessages().add(error);
                            }
                        }
                    } finally {
                        doneLatch.countDown();
                        int completed = successCount.get() + failCount.get();
                        if (completed % 100 == 0 || completed == totalRequests) {
                            if (progressListener != null) progressListener.accept(getStatus());
                        }
                    }
                });
            }

            readyLatch.await();
            currentStatus.setMessage("🚀 Firing all requests!");
            if (progressListener != null) progressListener.accept(currentStatus);
            
            long startTime = System.currentTimeMillis();
            startLatch.countDown();
            doneLatch.await();
            long endTime = System.currentTimeMillis();

            currentStatus.setStatus("COMPLETED");
            currentStatus.setDurationMs(endTime - startTime);
            currentStatus.setTps(totalRequests / ((endTime - startTime) / 1000.0));
            currentStatus.setMessage("Test completed successfully in " + (endTime - startTime) + "ms");
            if (progressListener != null) progressListener.accept(currentStatus);
        }
    }

    private void setupAccount(String accountId, BigDecimal balance) {
        if (!accountRepository.existsByAccountId(accountId)) {
            createAccount(accountId, balance);
        } else {
            // Update balance to initial for the test
            accountRepository.findByAccountId(accountId).ifPresent(acc -> {
                acc.setBalance(balance);
                accountRepository.save(acc);
            });
        }
    }

    private void createAccount(String accountId, BigDecimal balance) {
        com.isakatirci.MVP.entity.Account account = com.isakatirci.MVP.entity.Account.builder()
                .accountId(accountId)
                .balance(balance)
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);
    }

    private record TestRequest(String idempotencyKey, String from, String to, BigDecimal amount) {}
}
