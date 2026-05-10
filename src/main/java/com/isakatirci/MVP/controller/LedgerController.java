package com.isakatirci.MVP.controller;

import com.isakatirci.MVP.dto.CreateAccountRequest;
import com.isakatirci.MVP.dto.CreateTransferRequest;
import com.isakatirci.MVP.dto.TransferResponse;
import com.isakatirci.MVP.entity.Account;
import com.isakatirci.MVP.entity.TransactionLedger;
import com.isakatirci.MVP.repository.AccountRepository;
import com.isakatirci.MVP.repository.TransactionLedgerRepository;
import com.isakatirci.MVP.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Slf4j
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;
    private final AccountRepository accountRepository;
    private final TransactionLedgerRepository transactionRepository;
    private final com.isakatirci.MVP.repository.IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * Create a transfer between two accounts.
     * Requires Idempotency-Key header for exactly-once semantics.
     */
    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) throws Exception {
        TransferResponse response = ledgerService.createTransfer(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get transfer details by Idempotency Key (GUID).
     */
    @GetMapping("/transfers/{idempotencyKey}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable String idempotencyKey) {
        return idempotencyKeyRepository.findByKey(idempotencyKey)
                .map(ik -> {
                    TransferResponse response = TransferResponse.builder()
                            .transactionId(ik.getTransactionId())
                            .status(ik.getStatus())
                            .timestamp(ik.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                            .build();
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get account balance.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        return accountRepository.findByAccountId(accountId)
                .map(account -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("accountId", account.getAccountId());
                    response.put("balance", ledgerService.calculateBalance(accountId));
                    response.put("timestamp", System.currentTimeMillis());
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new account. Body-based request.
     */
    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = Account.builder()
                .accountId(request.getAccountId())
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);

        if (request.getInitialBalance() != null && request.getInitialBalance().compareTo(java.math.BigDecimal.ZERO) > 0) {
            TransactionLedger initTxn = TransactionLedger.builder()
                    .transactionId(java.util.UUID.randomUUID().toString())
                    .fromAccountId("SYSTEM")
                    .toAccountId(request.getAccountId())
                    .amount(request.getInitialBalance())
                    .status(TransactionLedger.TransactionStatus.COMPLETED)
                    .metadata("Initial Balance")
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionRepository.save(initTxn);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountId", account.getAccountId());
        response.put("balance", request.getInitialBalance());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * RESET DATABASE (Debug only). Truncates all tables.
     */
    @DeleteMapping("/debug/reset")
    public ResponseEntity<Void> resetDatabase() {
        ledgerService.resetDatabase();
        return ResponseEntity.noContent().build();
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("database", "CONNECTED");
        response.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}
