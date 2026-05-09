package com.isakatirci.MVP;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
// ==================== ENTITIES ====================

@Entity
@Table(name = "accounts", indexes = @Index(name = "idx_account_id", columnList = "account_id", unique = true))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String accountId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Version
    private Long version;
}

@Entity
@Table(name = "transaction_ledgers", indexes = {
        @Index(name = "idx_txn_id", columnList = "transaction_id", unique = true),
        @Index(name = "idx_from_account", columnList = "from_account_id"),
        @Index(name = "idx_to_account", columnList = "to_account_id"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class TransactionLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String transactionId;

    @Column(nullable = false, length = 50)
    private String fromAccountId;

    @Column(nullable = false, length = 50)
    private String toAccountId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    @Column(length = 500)
    private String metadata;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Version
    private Long version;

    enum TransactionStatus {
        PENDING, COMPLETED, COMPENSATED, FAILED
    }
}

@Entity
@Table(name = "outbox", indexes = @Index(name = "idx_outbox_status", columnList = "status"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class Outbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String eventId;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OutboxStatus status;

    @Column(nullable = false)
    private Integer retryCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime processedAt;

    enum OutboxStatus {
        PENDING, PUBLISHED, FAILED
    }
}

// ==================== REPOSITORIES ====================

@Repository
interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountId(String accountId);

    @Query(value = "SELECT * FROM accounts WHERE account_id = ?1 FOR UPDATE", nativeQuery = true)
    Optional<Account> findByAccountIdWithLock(String accountId);
}

@Repository
interface TransactionLedgerRepository extends JpaRepository<TransactionLedger, Long> {
    Optional<TransactionLedger> findByTransactionId(String transactionId);
}

@Repository
interface OutboxRepository extends JpaRepository<Outbox, Long> {
    List<Outbox> findByStatusOrderByCreatedAtAsc(Outbox.OutboxStatus status);

    List<Outbox> findByStatusAndRetryCountLessThanOrderByCreatedAtAsc(
            Outbox.OutboxStatus status, Integer maxRetries);
}

// ==================== DTOs ====================

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class CreateTransferRequest {
    private String transactionId;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private String metadata;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class TransferResponse {
    private String transactionId;
    private String status;
    private BigDecimal fromBalance;
    private BigDecimal toBalance;
    private Long timestamp;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class HealthResponse {
    private String status;
    private String database;
    private Long uptime;
    private Long timestamp;
}

// ==================== BUSINESS LOGIC ====================

@Service
@Slf4j
@AllArgsConstructor
class LedgerService {
    private final AccountRepository accountRepository;
    private final TransactionLedgerRepository transactionRepository;
    private final OutboxRepository outboxRepository;

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 100;

    @Transactional
    public TransferResponse createTransfer(CreateTransferRequest request) throws Exception {
        String transactionId = request.getTransactionId();

        // Check idempotency
        Optional<TransactionLedger> existing = transactionRepository.findByTransactionId(transactionId);
        if (existing.isPresent()) {
            log.info("Idempotent transfer: {}", transactionId);
            return buildResponse(existing.get());
        }

        // Execute with retry logic
        return executeWithRetry(() -> performTransfer(request));
    }

    private TransferResponse performTransfer(CreateTransferRequest request) throws Exception {
        String fromId = request.getFromAccountId();
        String toId = request.getToAccountId();
        BigDecimal amount = request.getAmount();

        // Lock in order to prevent deadlock (sort by account ID)
        Account fromAccount, toAccount;
        if (fromId.compareTo(toId) < 0) {
            fromAccount = accountRepository.findByAccountIdWithLock(fromId)
                    .orElseThrow(() -> new IllegalArgumentException("From account not found"));
            toAccount = accountRepository.findByAccountIdWithLock(toId)
                    .orElseThrow(() -> new IllegalArgumentException("To account not found"));
        } else {
            toAccount = accountRepository.findByAccountIdWithLock(toId)
                    .orElseThrow(() -> new IllegalArgumentException("To account not found"));
            fromAccount = accountRepository.findByAccountIdWithLock(fromId)
                    .orElseThrow(() -> new IllegalArgumentException("From account not found"));
        }

        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient balance");
        }

        // Create transaction ledger
        TransactionLedger txn = TransactionLedger.builder()
                .transactionId(request.getTransactionId())
                .fromAccountId(fromId)
                .toAccountId(toId)
                .amount(amount)
                .status(TransactionLedger.TransactionStatus.COMPLETED)
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .build();
        transactionRepository.save(txn);

        // Update balances
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        accountRepository.saveAll(List.of(fromAccount, toAccount));

        // Create outbox event
        String payload = String.format(
                "{\"transactionId\":\"%s\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s}",
                request.getTransactionId(), fromId, toId, amount
        );
        Outbox event = Outbox.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TRANSFER_COMPLETED")
                .payload(payload)
                .status(Outbox.OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(event);

        log.info("Transfer completed: {} from {} to {} amount {}",
                request.getTransactionId(), fromId, toId, amount);

        return buildResponse(txn);
    }

    private TransferResponse buildResponse(TransactionLedger txn) {
        Account from = accountRepository.findByAccountId(txn.getFromAccountId()).orElse(null);
        Account to = accountRepository.findByAccountId(txn.getToAccountId()).orElse(null);

        return TransferResponse.builder()
                .transactionId(txn.getTransactionId())
                .status(txn.getStatus().toString())
                .fromBalance(from != null ? from.getBalance() : BigDecimal.ZERO)
                .toBalance(to != null ? to.getBalance() : BigDecimal.ZERO)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private <T> T executeWithRetry(RetryableOperation<T> operation) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return operation.execute();
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES - 1) {
                    long backoffMs = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt);
                    log.warn("Retry {} after {}ms: {}", attempt + 1, backoffMs, e.getMessage());
                    Thread.sleep(backoffMs);
                }
            }
        }
        throw lastException;
    }

    @FunctionalInterface
    interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}

// ==================== OUTBOX RELAY ====================

@Service
@Slf4j
@AllArgsConstructor
class OutboxRelayService {
    private final OutboxRepository outboxRepository;
    private final RestTemplate restTemplate;
    private static final String SETTLEMENT_SERVICE_URL = "${settlement.service.url:http://settlement:8081/settle}";
    private static final int THREAD_POOL_SIZE = 4;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 500, initialDelay = 5000)
    public void relayEvents() {
        List<Outbox> pendingEvents = outboxRepository.findByStatusAndRetryCountLessThanOrderByCreatedAtAsc(
                Outbox.OutboxStatus.PENDING, 5);

        if (pendingEvents.isEmpty()) {
            return;
        }

        Map<String, List<Outbox>> partitioned = new HashMap<>();
        for (Outbox event : pendingEvents) {
            String key = event.getPayload().contains("fromAccountId") ?
                    extractAccountId(event.getPayload()) : UUID.randomUUID().toString();
            partitioned.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (List<Outbox> batch : partitioned.values()) {
            futures.add(CompletableFuture.runAsync(() -> processBatch(batch), executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void processBatch(List<Outbox> batch) {
        for (Outbox event : batch) {
            try {
                publishEvent(event);
                event.setStatus(Outbox.OutboxStatus.PUBLISHED);
                event.setProcessedAt(LocalDateTime.now());
                outboxRepository.save(event);
                log.info("Event published: {}", event.getEventId());
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= 5) {
                    event.setStatus(Outbox.OutboxStatus.FAILED);
                    log.error("Event failed after retries: {}", event.getEventId(), e);
                }
                outboxRepository.save(event);
            }
        }
    }

    private void publishEvent(Outbox event) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    SETTLEMENT_SERVICE_URL,
                    event.getPayload(),
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Settlement service returned " + response.getStatusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish event", e);
        }
    }

    private String extractAccountId(String payload) {
        return payload.split("\"fromAccountId\":\"")[1].split("\"")[0];
    }
}

// ==================== CONTROLLERS ====================

@RestController
@RequestMapping("/api/v1")
@Slf4j
@AllArgsConstructor
class LedgerController {
    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;

    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> createTransfer(@RequestBody CreateTransferRequest request) {
        try {
            TransferResponse response = ledgerService.createTransfer(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Transfer failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        return accountRepository.findByAccountId(accountId)
                .map(account -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("accountId", account.getAccountId());
                    response.put("balance", account.getBalance());
                    response.put("timestamp", System.currentTimeMillis());
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createAccount(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "0") BigDecimal initialBalance) {
        Account account = Account.builder()
                .accountId(accountId)
                .balance(initialBalance)
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", account.getAccountId());
        response.put("balance", account.getBalance());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(HealthResponse.builder()
                .status("UP")
                .database("CONNECTED")
                .uptime(ManagementFactory.getRuntimeMXBean().getUptime())
                .timestamp(System.currentTimeMillis())
                .build());
    }
}

// ==================== CONFIG ====================

@org.springframework.context.annotation.Configuration
class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
