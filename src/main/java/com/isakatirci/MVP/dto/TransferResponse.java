package com.isakatirci.MVP.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferResponse {
    private String transactionId;
    private String status;
    private BigDecimal fromBalance;
    private BigDecimal toBalance;
    private Long timestamp;
}
