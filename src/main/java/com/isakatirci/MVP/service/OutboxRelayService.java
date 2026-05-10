package com.isakatirci.MVP.service;
 
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isakatirci.MVP.entity.Outbox;
import com.isakatirci.MVP.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
 
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
 
/**
 * Outbox relay service that polls for pending events and publishes them
 * to Kafka topics.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = false)
public class OutboxRelayService {
 
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
 
    private static final int MAX_RETRIES = 5;
    private static final int BATCH_SIZE = 50;
 
    public OutboxRelayService(OutboxRepository outboxRepository, KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }
 
    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}", initialDelay = 5000)
    @Transactional
    public void relayEvents() {
        List<Outbox> pendingEvents = outboxRepository.claimPendingEvents(MAX_RETRIES, BATCH_SIZE);
 
        if (pendingEvents.isEmpty()) {
            return;
        }
 
        for (Outbox event : pendingEvents) {
            processEvent(event);
        }
    }
 
    private void processEvent(Outbox event) {
        try {
            publishEvent(event);
            event.setStatus(Outbox.OutboxStatus.PUBLISHED);
            event.setProcessedAt(LocalDateTime.now());
            outboxRepository.save(event);
            log.info("Event published to Kafka: {} (Type: {})", event.getEventId(), event.getEventType());
        } catch (Exception e) {
            event.setRetryCount(event.getRetryCount() + 1);
            event.setLastError(e.getMessage());
 
            if (event.getRetryCount() >= MAX_RETRIES) {
                event.setStatus(Outbox.OutboxStatus.FAILED);
                log.error("Event permanently failed after {} retries: {}", MAX_RETRIES, event.getEventId());
            } else {
                // Exponential backoff with jitter
                long backoffMs = 500L * (long) Math.pow(2, event.getRetryCount());
                long jitter = ThreadLocalRandom.current().nextLong(backoffMs / 4);
                event.setNextAttemptAt(LocalDateTime.now().plusNanos((backoffMs + jitter) * 1_000_000));
                log.warn("Event retry scheduled: {} (attempt {})", event.getEventId(), event.getRetryCount());
            }
            outboxRepository.save(event);
        }
    }
 
    private void publishEvent(Outbox event) throws Exception {
        String topic = switch (event.getEventType()) {
            case "TRANSFER_REQUEST" -> "transfer-requests";
            case "TRANSFER_SUCCESS" -> "transfer-success";
            case "TRANSFER_FAILED" -> "transfer-failed";
            default -> throw new IllegalArgumentException("Unknown event type: " + event.getEventType());
        };
 
        JsonNode payload = objectMapper.readTree(event.getPayload());
        
        // Use eventId (or part of it) as message key for ordering
        String messageKey = event.getEventId();
        if (messageKey.endsWith("-success") || messageKey.endsWith("-failed")) {
            messageKey = messageKey.split("-")[0];
        }
 
        kafkaTemplate.send(topic, messageKey, payload).get(); // Synchronous send to ensure durability before updating status
    }
}
