package com.example.demo;

import com.example.pricing.LineItem;
import com.example.pricing.Order;
import com.example.pricing.PricingService;
import com.example.pricing.Quote;
import com.example.pricing.QuoteOptions;
import com.example.pricing.UnsupportedCurrencyException;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Endpoints:
 *
 * <pre>
 * GET /health
 * GET /quote                       default: 3 units, USD
 * GET /quote?currency=EUR          convert (USD, EUR, GBP, JPY)
 * GET /quote?qty=12                bulk discount (>= 10 units)
 * GET /quote?slow=true             slow discount-rules lookup (~300ms)
 * GET /quote?currency=XYZ          unsupported currency -> error
 * </pre>
 */
public class DemoApp {

  private static final Logger LOG = Logger.getLogger(DemoApp.class.getName());
  private static final PricingService PRICING = new PricingService();
  private static final AtomicLong ORDER_SEQ = new AtomicLong(1000);

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/health", ex -> respond(ex, 200, "ok\n"));
    server.createContext("/quote", DemoApp::handleQuote);
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    LOG.info("java-hybrid-demo listening on :" + port);
  }

  // The agent's built-in HttpServer instrumentation doesn't start transactions on every JVM,
  // so start the transaction explicitly; the library's OTel spans then nest under it.
  @Trace(dispatcher = true)
  private static void handleQuote(HttpExchange ex) throws IOException {
    NewRelic.setTransactionName("Web", "/quote");
    Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
    String currency = params.getOrDefault("currency", "USD").toUpperCase();
    int qty = Integer.parseInt(params.getOrDefault("qty", "3"));
    boolean slow = Boolean.parseBoolean(params.getOrDefault("slow", "false"));

    Order order =
        new Order(
            "ORD-" + ORDER_SEQ.incrementAndGet(),
            currency,
            List.of(
                new LineItem("WIDGET-1", new BigDecimal("19.99"), qty),
                new LineItem("GADGET-7", new BigDecimal("49.50"), 1)));

    LOG.info("Quote request " + order.id() + " currency=" + currency + " qty=" + qty + " slow=" + slow);
    try {
      Quote q = PRICING.quote(order, new QuoteOptions(slow));
      LOG.info("Quote request " + order.id() + " succeeded, total=" + q.total());
      respond(
          ex,
          200,
          String.format(
              "{\"orderId\":\"%s\",\"currency\":\"%s\",\"subtotal\":%s,\"discount\":%s,"
                  + "\"tax\":%s,\"total\":%s}%n",
              q.orderId(), q.currency(), q.subtotal(), q.discount(), q.tax(), q.total()));
    } catch (UnsupportedCurrencyException e) {
      LOG.log(Level.WARNING, "Quote request " + order.id() + " failed: " + e.getMessage(), e);
      NewRelic.noticeError(e);
      respond(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}\n");
    }
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> out = new HashMap<>();
    if (query == null) {
      return out;
    }
    for (String pair : query.split("&")) {
      String[] kv = pair.split("=", 2);
      out.put(kv[0], kv.length > 1 ? kv[1] : "");
    }
    return out;
  }

  private static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (var os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
