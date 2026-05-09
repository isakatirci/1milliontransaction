package com.isakatirci.MVP.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTransferRequest {

    @NotBlank(message = "fromAccountId is required")
    private String fromAccountId;

    @NotBlank(message = "toAccountId is required")
    private String toAccountId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "valueDate is required")
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.isakatirci.MVP.config.CustomLocalDateDeserializer.class)
    private java.time.LocalDate valueDate;

    private String metadata;
}
