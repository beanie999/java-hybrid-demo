package com.example.pricing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Prices an order. Span tree produced per call:
 *
 * <pre>
 * pricing.quote
 * ├── pricing.applyDiscounts
 * │   └── pricing.lookupDiscountRules
 * ├── pricing.calculateTax
 * └── pricing.convertCurrency
 * </pre>
 *
 * Metrics recorded per call (attribute {@code currency}; the counter also has {@code outcome}):
 *
 * <ul>
 *   <li>{@code pricing.quotes} - counter of quotes, by outcome (success/error)
 *   <li>{@code pricing.quote.duration} - histogram of quote latency in ms
 *   <li>{@code pricing.quote.value} - histogram of the quote total, in USD
 * </ul>
 *
 * Only the OpenTelemetry API is used (traces, logs and metrics); signals are exported by whatever agent/SDK
 * the host registers.
 */
public class PricingService {

  private static final String INSTRUMENTATION_NAME = "com.example.pricing";

  private static final Map<String, BigDecimal> USD_RATES =
      Map.of(
          "USD", new BigDecimal("1.00"),
          "EUR", new BigDecimal("0.92"),
          "GBP", new BigDecimal("0.79"),
          "JPY", new BigDecimal("149.50"));

  private static final BigDecimal TAX_RATE = new BigDecimal("0.08");
  private static final BigDecimal BULK_DISCOUNT_RATE = new BigDecimal("0.10");
  private static final int BULK_THRESHOLD = 10;

  private final Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME, "1.0.0");
  private final Logger logger =
      GlobalOpenTelemetry.get().getLogsBridge().loggerBuilder(INSTRUMENTATION_NAME).build();

  public Quote quote(Order order) {
    return quote(order, QuoteOptions.DEFAULT);
  }

  private final Meter meter = GlobalOpenTelemetry.getMeter(INSTRUMENTATION_NAME);
  private final LongCounter quoteCounter =
      meter
          .counterBuilder("pricing.quotes")
          .setDescription("Number of quotes requested")
          .setUnit("{quote}")
          .build();
  private final DoubleHistogram quoteDuration =
      meter
          .histogramBuilder("pricing.quote.duration")
          .setDescription("Time taken to price an order")
          .setUnit("ms")
          .build();
  private final DoubleHistogram quoteValue =
      meter
          .histogramBuilder("pricing.quote.value")
          .setDescription("Quote total before currency conversion")
          .setUnit("USD")
          .build();

  private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
  private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

  public Quote quote(Order order, QuoteOptions options) {
    long startNanos = System.nanoTime();
    String outcome = "success";
    try {
      return priceOrder(order, options);
    } catch (RuntimeException e) {
      outcome = "error";
      throw e;
    } finally {
      Attributes currency = Attributes.of(CURRENCY, order.currency());
      quoteCounter.add(1, Attributes.of(CURRENCY, order.currency(), OUTCOME, outcome));
      quoteDuration.record((System.nanoTime() - startNanos) / 1_000_000.0, currency);
    }
  }

  private Quote priceOrder(Order order, QuoteOptions options) {
    return traced(
        "pricing.quote",
        span -> {
          span.setAttribute("order.id", order.id());
          span.setAttribute("order.currency", order.currency());
          span.setAttribute("order.item.count", order.items().size());
          log(Severity.INFO, "Pricing order " + order.id() + " in " + order.currency(), order);

          BigDecimal subtotalUsd =
              order.items().stream()
                  .map(i -> i.unitPriceUsd().multiply(BigDecimal.valueOf(i.quantity())))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);

          BigDecimal discountUsd = applyDiscounts(order, subtotalUsd, options);
          BigDecimal taxUsd = calculateTax(subtotalUsd.subtract(discountUsd));

          Quote usd =
              new Quote(
                  order.id(),
                  "USD",
                  subtotalUsd,
                  discountUsd,
                  taxUsd,
                  subtotalUsd.subtract(discountUsd).add(taxUsd));
          quoteValue.record(usd.total().doubleValue(), Attributes.of(CURRENCY, order.currency()));
          Quote result = convertCurrency(usd, order.currency());

          span.setAttribute("quote.total", result.total().doubleValue());
          log(Severity.INFO, "Quote complete for " + order.id() + ", total " + result.total(), order);
          return result;
        });
  }

  private BigDecimal applyDiscounts(Order order, BigDecimal subtotal, QuoteOptions options) {
    return traced(
        "pricing.applyDiscounts",
        span -> {
          BigDecimal rate = lookupDiscountRules(order, options);
          BigDecimal discount = subtotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
          span.setAttribute("discount.rate", rate.doubleValue());
          span.setAttribute("discount.amount", discount.doubleValue());
          log(Severity.INFO, "Applied discount rate " + rate + " to " + order.id(), order);
          return discount;
        });
  }

  /** Simulates a call to a rules store; bulk orders get a discount. */
  private BigDecimal lookupDiscountRules(Order order, QuoteOptions options) {
    return traced(
        "pricing.lookupDiscountRules",
        SpanKind.CLIENT,
        span -> {
          span.setAttribute("rules.slow", options.slowRules());
          if (options.slowRules()) {
            log(Severity.WARN, "Discount rules store is slow for " + order.id(), order);
          }
          sleep(options.slowRules() ? 300 : 20);
          int units = order.items().stream().mapToInt(LineItem::quantity).sum();
          span.setAttribute("order.unit.count", units);
          return units >= BULK_THRESHOLD ? BULK_DISCOUNT_RATE : BigDecimal.ZERO;
        });
  }

  private BigDecimal calculateTax(BigDecimal taxableUsd) {
    return traced(
        "pricing.calculateTax",
        span -> {
          sleep(10);
          BigDecimal tax = taxableUsd.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
          span.setAttribute("tax.rate", TAX_RATE.doubleValue());
          span.setAttribute("tax.amount", tax.doubleValue());
          return tax;
        });
  }

  /** Simulates a call to an FX service; throws for currencies without a rate. */
  private Quote convertCurrency(Quote usd, String currency) {
    return traced(
        "pricing.convertCurrency",
        SpanKind.CLIENT,
        span -> {
          span.setAttribute("currency.from", "USD");
          span.setAttribute("currency.to", currency);
          sleep(40);
          BigDecimal rate = USD_RATES.get(currency);
          if (rate == null) {
            logger
                .logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setSeverityText("ERROR")
                .setBody("No exchange rate for currency " + currency)
                .setAllAttributes(Attributes.of(AttributeKey.stringKey("order.id"), usd.orderId()))
                .emit();
            throw new UnsupportedCurrencyException(currency);
          }
          span.setAttribute("currency.rate", rate.doubleValue());
          return new Quote(
              usd.orderId(),
              currency,
              convert(usd.subtotal(), rate),
              convert(usd.discount(), rate),
              convert(usd.tax(), rate),
              convert(usd.total(), rate));
        });
  }

  private static BigDecimal convert(BigDecimal usd, BigDecimal rate) {
    return usd.multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }

  private <T> T traced(String name, SpanFunction<T> body) {
    return traced(name, SpanKind.INTERNAL, body);
  }

  private <T> T traced(String name, SpanKind kind, SpanFunction<T> body) {
    Span span = tracer.spanBuilder(name).setSpanKind(kind).startSpan();
    try (Scope ignored = span.makeCurrent()) {
      return body.apply(span);
    } catch (RuntimeException e) {
      span.recordException(e);
      span.setAttribute(AttributeKey.stringKey("error.type"), e.getClass().getSimpleName());
      span.setStatus(StatusCode.ERROR, e.getMessage());
      throw e;
    } finally {
      span.end();
    }
  }

  private void log(Severity severity, String message, Order order) {
    logger
        .logRecordBuilder()
        .setSeverity(severity)
        .setSeverityText(severity.name())
        .setBody(message)
        .setAllAttributes(Attributes.of(AttributeKey.stringKey("order.id"), order.id()))
        .emit();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @FunctionalInterface
  private interface SpanFunction<T> {
    T apply(Span span);
  }
}
