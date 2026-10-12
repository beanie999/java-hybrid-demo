package com.example.pricing;

import java.math.BigDecimal;

/** A single order line. Unit price is expressed in USD (the library's base currency). */
public record LineItem(String sku, BigDecimal unitPriceUsd, int quantity) {}
