package com.example.pricing;

/** Thrown when a quote is requested in a currency the library has no rate for. */
public class UnsupportedCurrencyException extends RuntimeException {
  public UnsupportedCurrencyException(String currency) {
    super("Unsupported currency: " + currency);
  }
}
