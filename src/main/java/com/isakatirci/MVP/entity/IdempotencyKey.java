package com.isakatirci.MVP.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_keys")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {
    @Id
    @Column(length = 128)
    private String key;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(length = 100)
    private String transactionId;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String responseSnapshot;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime expiresAt;
}
