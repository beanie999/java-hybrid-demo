package com.example.pricing;

import java.math.BigDecimal;

/** Result of pricing an order. All amounts are in {@code currency}. */
public record Quote(
    String orderId,
    String currency,
    BigDecimal subtotal,
    BigDecimal discount,
    BigDecimal tax,
    BigDecimal total) {}
