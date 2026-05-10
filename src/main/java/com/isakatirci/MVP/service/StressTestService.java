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

    private void executeTest(int totalRequests, Consumer<StressTestStatus> progressListener) throws Exception {
        int transactionCountPerPair = totalRequests / 2;
        BigDecimal amount = new BigDecimal("5.00");
        BigDecimal initialBalance = new BigDecimal("100000.00");

        // 1. SETUP: Ensure accounts exist
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

        currentStatus.setMessage("Launching " + totalRequests + " virtual threads...");
        if (progressListener != null) progressListener.accept(currentStatus);

        // 3. EXECUTION: Virtual Threads
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TestRequest testReq : requests) {
                executor.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await(); // Sync point for all threads

                        CreateTransferRequest req = new CreateTransferRequest();
                        req.setFromAccountId(testReq.from);
                        req.setToAccountId(testReq.to);
                        req.setAmount(testReq.amount);
                        req.setMetadata("stress-test-ui");

                        ledgerService.createTransfer(testReq.idempotencyKey, req);
                        int s = successCount.incrementAndGet();
                        currentStatus.setSuccessful(s);
                    } catch (Exception e) {
                        int f = failCount.incrementAndGet();
                        currentStatus.setFailed(f);
                        log.debug("Transfer failed: {}", e.getMessage());
                    } finally {
                        doneLatch.countDown();
                        // Report progress every 100 requests or at the end
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
