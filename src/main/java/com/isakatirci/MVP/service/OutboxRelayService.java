package com.isakatirci.MVP.service;

import com.isakatirci.MVP.entity.Outbox;
import com.isakatirci.MVP.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Outbox relay service that polls for pending events and publishes them
 * to the settlement service. Disabled by default for MVP.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = false)
public class OutboxRelayService {

    private final OutboxRepository outboxRepository;
    private final RestTemplate restTemplate;

    @Value("${settlement.service.url:http://settlement:8081/settle}")
    private String settlementServiceUrl;

    private static final int MAX_RETRIES = 5;
    private static final int BATCH_SIZE = 50;

    public OutboxRelayService(OutboxRepository outboxRepository, RestTemplate restTemplate) {
        this.outboxRepository = outboxRepository;
        this.restTemplate = restTemplate;
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
            log.info("Event published: {}", event.getEventId());
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

    private void publishEvent(Outbox event) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                settlementServiceUrl,
                event.getPayload(),
                String.class
        );
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Settlement service returned " + response.getStatusCode());
        }
    }
}
