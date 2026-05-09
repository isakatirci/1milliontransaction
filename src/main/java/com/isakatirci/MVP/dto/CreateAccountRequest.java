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
public class CreateAccountRequest {

    @NotBlank(message = "accountId is required")
    private String accountId;

    @NotNull(message = "initialBalance is required")
    @DecimalMin(value = "0", message = "initialBalance must be >= 0")
    private BigDecimal initialBalance;
}
