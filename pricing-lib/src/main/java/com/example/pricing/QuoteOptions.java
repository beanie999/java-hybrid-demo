package com.example.pricing;

/**
 * Knobs that let a caller trigger demo scenarios.
 *
 * @param slowRules when true, the discount-rules lookup takes ~300ms
 */
public record QuoteOptions(boolean slowRules) {
  public static final QuoteOptions DEFAULT = new QuoteOptions(false);
}
