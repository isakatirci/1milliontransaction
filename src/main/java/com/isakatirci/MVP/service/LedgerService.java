package com.isakatirci.MVP.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isakatirci.MVP.dto.CreateTransferRequest;
import com.isakatirci.MVP.dto.TransferResponse;
import com.isakatirci.MVP.entity.Account;
import com.isakatirci.MVP.entity.IdempotencyKey;
import com.isakatirci.MVP.entity.Outbox;
import com.isakatirci.MVP.entity.TransactionLedger;
import com.isakatirci.MVP.exception.AccountNotFoundException;
import com.isakatirci.MVP.exception.DuplicateRequestException;
import com.isakatirci.MVP.exception.IdempotencyConflictException;
import com.isakatirci.MVP.exception.InsufficientBalanceException;
import com.isakatirci.MVP.repository.AccountRepository;
import com.isakatirci.MVP.repository.IdempotencyKeyRepository;
import com.isakatirci.MVP.repository.OutboxRepository;
import com.isakatirci.MVP.repository.TransactionLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionLedgerRepository transactionRepository;
    private final OutboxRepository outboxRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 100;

    /**
     * Creates a transfer with full idempotency support.
     *
     * @param idempotencyKey unique key from Idempotency-Key header
     * @param request        transfer request details
     * @return transfer response
     */
    public TransferResponse createTransfer(String idempotencyKey, CreateTransferRequest request) throws Exception {
        // Validate self-transfer
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        String requestHash = computeHash(request);

        // Execute with retry logic — each retry gets a fresh transaction
        return executeWithRetry(() -> transactionTemplate.execute(status -> {
            try {
                // Check idempotency inside transaction
                Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByKey(idempotencyKey);
                if (existingKey.isPresent()) {
                    IdempotencyKey ik = existingKey.get();

                    // Same key but different request body → conflict
                    if (!ik.getRequestHash().equals(requestHash)) {
                        throw new IdempotencyConflictException(idempotencyKey);
                    }

                    // Same key, same body → return cached response
                    log.info("Idempotent hit for key: {}", idempotencyKey);
                    return deserializeResponse(ik.getResponseSnapshot());
                }

                // Check 40s duplicate request
            /*    boolean isDuplicate = idempotencyKeyRepository.existsByRequestHashAndCreatedAtAfterAndKeyNot(
                        requestHash, LocalDateTime.now().minusNanos(1), idempotencyKey);
                if (isDuplicate) {
                    throw new DuplicateRequestException("Duplicate request detected within the last 40 seconds. Please try again later.");
                }*/

                // Execute the transfer
                TransferResponse response = performTransfer(idempotencyKey, request);

                // Store idempotency key with response snapshot
                IdempotencyKey ik = IdempotencyKey.builder()
                        .key(idempotencyKey)
                        .requestHash(requestHash)
                        .transactionId(response.getTransactionId())
                        .status("COMPLETED")
                        .responseSnapshot(serializeResponse(response))
                        .createdAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plusHours(24))
                        .build();
                idempotencyKeyRepository.save(ik);

                return response;
            } catch (IdempotencyConflictException | DuplicateRequestException | InsufficientBalanceException |
                     AccountNotFoundException | IllegalArgumentException e) {
                status.setRollbackOnly();
                throw e;
            } catch (RuntimeException e) {
                status.setRollbackOnly();
                throw e;
            }
        }));
    }

    public void resetDatabase() {
        transactionTemplate.execute(status -> {
            log.info("Resetting database: Deleting all records in order to respect FKs...");
            jdbcTemplate.execute("DELETE FROM transaction_ledgers");
            jdbcTemplate.execute("DELETE FROM outbox");
            jdbcTemplate.execute("DELETE FROM idempotency_keys");
            jdbcTemplate.execute("DELETE FROM accounts");
            log.info("Database reset complete.");
            return null;
        });
    }

    private TransferResponse performTransfer(String idempotencyKey, CreateTransferRequest request) {
        String fromId = request.getFromAccountId();
        String toId = request.getToAccountId();
        BigDecimal amount = request.getAmount();

        // Lock in deterministic order to prevent deadlocks
        Account fromAccount, toAccount;
        if (fromId.compareTo(toId) < 0) {
            fromAccount = accountRepository.findByAccountIdWithLock(fromId)
                    .orElseThrow(() -> new AccountNotFoundException(fromId));
            toAccount = accountRepository.findByAccountIdWithLock(toId)
                    .orElseThrow(() -> new AccountNotFoundException(toId));
        } else {
            toAccount = accountRepository.findByAccountIdWithLock(toId)
                    .orElseThrow(() -> new AccountNotFoundException(toId));
            fromAccount = accountRepository.findByAccountIdWithLock(fromId)
                    .orElseThrow(() -> new AccountNotFoundException(fromId));
        }

        // Balance check (also enforced by DB CHECK constraint)
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(fromId);
        }

        // Create transaction ledger record
        String transactionId = UUID.randomUUID().toString();
        TransactionLedger txn = TransactionLedger.builder()
                .transactionId(transactionId)
                .fromAccountId(fromId)
                .toAccountId(toId)
                .amount(amount)
                .status(TransactionLedger.TransactionStatus.COMPLETED)
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .build();
        transactionRepository.save(txn);

        // Update balances atomically
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        accountRepository.saveAll(List.of(fromAccount, toAccount));

        // Create outbox event (same transaction)
        String payload = String.format(
                "{\"transactionId\":\"%s\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s}",
                transactionId, fromId, toId, amount
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

        log.info("Transfer completed: {} | {} -> {} | amount={}", transactionId, fromId, toId, amount);

        return TransferResponse.builder()
                .transactionId(transactionId)
                .status("COMPLETED")
                .fromBalance(fromAccount.getBalance())
                .toBalance(toAccount.getBalance())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ==================== Retry Logic ====================

    private <T> T executeWithRetry(RetryableOperation<T> operation) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return operation.execute();
            } catch (IdempotencyConflictException | DuplicateRequestException | InsufficientBalanceException |
                     AccountNotFoundException | IllegalArgumentException e) {
                // Non-retryable business exceptions — fail immediately
                throw e;
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

    // ==================== Hashing & Serialization ====================

    private String computeHash(CreateTransferRequest request) {
        try {
            String content = request.getFromAccountId() + "|" +
                    request.getToAccountId() + "|" +
                    request.getAmount().toPlainString() + "|" +
                    (request.getValueDate() != null ? request.getValueDate().toString() : "") + "|" +
                    (request.getMetadata() != null ? request.getMetadata() : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String serializeResponse(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Failed to serialize response", e);
            return "{}";
        }
    }

    private TransferResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, TransferResponse.class);
        } catch (Exception e) {
            log.error("Failed to deserialize response", e);
            return TransferResponse.builder().status("COMPLETED").timestamp(System.currentTimeMillis()).build();
        }
    }
}
