package com.isakatirci.MVP.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isakatirci.MVP.dto.CreateTransferRequest;
import com.isakatirci.MVP.dto.KafkaTransferMessage;
import com.isakatirci.MVP.dto.TransferResponse;
import com.isakatirci.MVP.entity.IdempotencyKey;
import com.isakatirci.MVP.entity.TransactionLedger;
import com.isakatirci.MVP.exception.IdempotencyConflictException;
import com.isakatirci.MVP.repository.IdempotencyKeyRepository;
import com.isakatirci.MVP.repository.TransactionLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerService {

    private final TransactionLedgerRepository transactionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    public TransferResponse createTransfer(String idempotencyKey, CreateTransferRequest request) throws Exception {
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        String requestHash = computeHash(request);
        String transactionId = UUID.randomUUID().toString();

        return transactionTemplate.execute(status -> {
            Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByKey(idempotencyKey);
            if (existingKey.isPresent()) {
                IdempotencyKey ik = existingKey.get();
                if (!ik.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(idempotencyKey);
                }
                return deserializeResponse(ik.getResponseSnapshot());
            }

            TransferResponse response = TransferResponse.builder()
                    .transactionId(transactionId)
                    .status("PENDING")
                    .timestamp(System.currentTimeMillis())
                    .build();

            IdempotencyKey ik = IdempotencyKey.builder()
                    .key(idempotencyKey)
                    .requestHash(requestHash)
                    .transactionId(transactionId)
                    .status("PENDING")
                    .responseSnapshot(serializeResponse(response))
                    .createdAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            idempotencyKeyRepository.save(ik);

            KafkaTransferMessage msg = KafkaTransferMessage.builder()
                    .idempotencyKey(idempotencyKey)
                    .transactionId(transactionId)
                    .fromAccountId(request.getFromAccountId())
                    .toAccountId(request.getToAccountId())
                    .amount(request.getAmount())
                    .metadata(request.getMetadata())
                    .build();

            kafkaTemplate.send("transfer-requests", transactionId, msg);

            return response;
        });
    }

    @KafkaListener(topics = "transfer-requests", groupId = "ledger-group")
    public void processTransferRequest(KafkaTransferMessage msg) {
        try {
            transactionTemplate.execute(status -> {
                BigDecimal fromBalance = calculateBalance(msg.getFromAccountId());

                TransactionLedger txn = TransactionLedger.builder()
                        .transactionId(msg.getTransactionId())
                        .fromAccountId(msg.getFromAccountId())
                        .toAccountId(msg.getToAccountId())
                        .amount(msg.getAmount())
                        .metadata(msg.getMetadata())
                        .createdAt(LocalDateTime.now())
                        .build();

                IdempotencyKey ik = idempotencyKeyRepository.findByKey(msg.getIdempotencyKey()).orElse(null);

                if (fromBalance.compareTo(msg.getAmount()) >= 0) {
                    txn.setStatus(TransactionLedger.TransactionStatus.COMPLETED);
                    transactionRepository.save(txn);
                    if (ik != null) {
                        ik.setStatus("COMPLETED");
                        idempotencyKeyRepository.save(ik);
                    }
                    kafkaTemplate.send("transfer-success", msg.getTransactionId(), msg);
                } else {
                    txn.setStatus(TransactionLedger.TransactionStatus.FAILED);
                    transactionRepository.save(txn);
                    if (ik != null) {
                        ik.setStatus("FAILED");
                        idempotencyKeyRepository.save(ik);
                    }
                    kafkaTemplate.send("transfer-failed", msg.getTransactionId(), msg);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to process transfer request: " + msg.getTransactionId(), e);
        }
    }

    @KafkaListener(topics = "transfer-success", groupId = "websocket-group")
    public void onTransferSuccess(KafkaTransferMessage msg) {
        messagingTemplate.convertAndSend("/topic/transfers", "SUCCESS:" + msg.getIdempotencyKey());
    }

    @KafkaListener(topics = "transfer-failed", groupId = "websocket-group")
    public void onTransferFailed(KafkaTransferMessage msg) {
        messagingTemplate.convertAndSend("/topic/transfers", "FAILED:" + msg.getIdempotencyKey());
    }

    public BigDecimal calculateBalance(String accountId) {
        BigDecimal bal = transactionRepository.calculateBalance(accountId);
        return bal != null ? bal : BigDecimal.ZERO;
    }

    public void resetDatabase() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM transaction_ledgers");
            jdbcTemplate.execute("DELETE FROM outbox");
            jdbcTemplate.execute("DELETE FROM idempotency_keys");
            jdbcTemplate.execute("DELETE FROM accounts");
            return null;
        });
    }

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
            return "{}";
        }
    }

    private TransferResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, TransferResponse.class);
        } catch (Exception e) {
            return TransferResponse.builder().status("PENDING").timestamp(System.currentTimeMillis()).build();
        }
    }
}
