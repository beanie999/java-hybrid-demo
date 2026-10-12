# java-hybrid-demo

A demo of the New Relic Java agent's **hybrid** model: an application instrumented with the New Relic APM agent that calls a shared library instrumented only with the **OpenTelemetry API**. Traces, logs and metrics from both sides show up together in New Relic.

## What it demonstrates

| Signal | App (`demo-app`) | Library (`pricing-lib`) |
|---|---|---|
| Traces | New Relic agent (`@Trace` starts the transaction) | OTel API spans, nested under the app's transaction |
| Logs | `java.util.logging`, forwarded by the agent | OTel Logs API |
| Metrics | n/a | OTel Metrics API |

The library depends only on `opentelemetry-api`. It has no SDK and no New Relic dependency, and it works unchanged without an agent (the calls are no-ops).

## Layout

```
├── pom.xml          parent POM (Java 21, OTel BOM 1.44.1)
├── pricing-lib/     pricing library, OTel API only
├── demo-app/        HttpServer app (shaded jar), uses the NR agent API for errors
├── run.sh           runs the app with the NR agent attached
└── load.sh          sends random requests to the app
```

### Span tree

```
Web//quote                          (New Relic transaction)
└── pricing.quote
    ├── pricing.applyDiscounts
    │   └── pricing.lookupDiscountRules   (~20ms, ~300ms with slow=true)
    ├── pricing.calculateTax
    └── pricing.convertCurrency           (fails for unsupported currencies)
```

## Prerequisites

- JDK 21 and Maven
- New Relic Java agent **9.1.0 or later** (OTel API support). Developed against 9.5.0.
- A New Relic **Ingest - License** key

Install the agent:

```bash
curl -L -o /tmp/newrelic-java.zip \
  https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip
unzip /tmp/newrelic-java.zip -d /tmp/newrelic-unzip
mkdir -p newrelic && mv /tmp/newrelic-unzip/newrelic/* newrelic/
java -jar newrelic/newrelic.jar -version
```

`run.sh` looks for the jar at `$NEWRELIC_JAR`, then `./newrelic/newrelic.jar`, then `../newrelic/newrelic.jar`.

## Build and run

```bash
export NEW_RELIC_LICENSE_KEY="<your-ingest-license-key>"
mvn -q package
./run.sh
```

The app listens on port 8080 (override with `PORT`). Never commit the license key; it is read from the environment only.

`run.sh` sets the following for you:

| Variable | Purpose |
|---|---|
| `NEW_RELIC_APP_NAME=java-hybrid-demo` | APM entity name (override by exporting it first) |
| `NEW_RELIC_OPENTELEMETRY_ENABLED=true` | Lets the agent capture OTel traces, metrics and logs |
| `OTEL_JAVA_GLOBAL_AUTOCONFIGURE_ENABLED=true` | OTel SDK autoconfiguration, needed by the agent's OTel support |
| `NEW_RELIC_DISTRIBUTED_TRACING_ENABLED=true` | Distributed tracing |

The app also needs `opentelemetry-sdk-extension-autoconfigure` and `opentelemetry-exporter-otlp` on its classpath, which `demo-app/pom.xml` includes. See the [agent's OpenTelemetry API support docs](https://docs.newrelic.com/docs/apm/agents/manage-apm-agents/opentelemetry-api-support/#prerequisites).

For an EU account, also set `NEW_RELIC_HOST=collector.eu01.nr-data.net`.

## Endpoints

| Endpoint | Scenario |
|---|---|
| `GET /health` | Baseline, no library calls |
| `GET /quote` | Normal path (3 units, USD) |
| `GET /quote?qty=12&currency=EUR` | Bulk discount (10+ units) and FX conversion |
| `GET /quote?currency=GBP` | Another currency (USD, EUR, GBP, JPY are supported) |
| `GET /quote?slow=true` | Slow discount-rules lookup (~300ms) |
| `GET /quote?currency=XYZ` | Error raised inside the library (HTTP 400) |

## Generate load

```bash
./load.sh [sleep_seconds=5] [executions=100]
./load.sh 1 60          # one request per second, 60 requests
```

Each execution sends one randomly chosen endpoint, then sleeps. Set `BASE_URL` to target another host.

## Queries in New Relic

Wait a minute or two for data. Note that attribute names differ by signal: spans and logs use `entity.name`, while OTel metrics use `service.name`.

### Traces

```sql
-- Spans by name
FROM Span SELECT count(*), average(duration.ms) FACET name
WHERE entity.name = 'java-hybrid-demo' SINCE 30 minutes ago

-- Library spans with their attributes
FROM Span SELECT name, trace.id, parent.id, order.id, currency.to, discount.rate, error.type
WHERE entity.name = 'java-hybrid-demo' AND name LIKE 'pricing.%' SINCE 30 minutes ago LIMIT 100
```

### Logs

```sql
-- App and library logs, correlated by trace
FROM Log SELECT timestamp, level, message, trace.id, span.id, order.id
WHERE entity.name = 'java-hybrid-demo' SINCE 30 minutes ago LIMIT 100

-- Errors and warnings only
FROM Log SELECT timestamp, level, message, trace.id
WHERE entity.name = 'java-hybrid-demo' AND level IN ('WARNING', 'WARN', 'ERROR', 'SEVERE')
SINCE 30 minutes ago
```

### Metrics (from `pricing-lib`)

| Metric | Type | Unit | Attributes |
|---|---|---|---|
| `pricing.quotes` | Counter | `{quote}` | `currency`, `outcome` (`success`/`error`) |
| `pricing.quote.duration` | Histogram | `ms` | `currency` |
| `pricing.quote.value` | Histogram | `USD` | `currency` |

```sql
-- Quote volume and error rate
FROM Metric SELECT sum(pricing.quotes) FACET currency, outcome
WHERE service.name = 'java-hybrid-demo' SINCE 15 minutes ago

-- Error rate over time
FROM Metric SELECT filter(sum(pricing.quotes), WHERE outcome = 'error') / sum(pricing.quotes) * 100 AS 'Error %'
WHERE service.name = 'java-hybrid-demo' SINCE 30 minutes ago TIMESERIES

-- Latency
FROM Metric SELECT average(pricing.quote.duration), percentile(pricing.quote.duration, 95)
FACET currency WHERE service.name = 'java-hybrid-demo' SINCE 15 minutes ago TIMESERIES

-- Order value
FROM Metric SELECT average(pricing.quote.value), max(pricing.quote.value)
FACET currency WHERE service.name = 'java-hybrid-demo' SINCE 15 minutes ago

-- Which attributes does a metric carry?
FROM Metric SELECT keyset() WHERE metricName = 'pricing.quotes' SINCE 30 minutes ago

-- Everything the library has sent
FROM Metric SELECT uniques(metricName) WHERE metricName LIKE 'pricing.%' SINCE 30 minutes ago
```

## Troubleshooting

- **Agent log:** `<agent dir>/logs/newrelic_agent.log`. Set `NEW_RELIC_LOG_LEVEL=finest` for detail.
- **No `pricing.*` spans, logs or metrics:** check the dependencies and variables in the Prerequisites section, and look for `Skipping instrumentation module ...opentelemetry...` in the agent log.
- **`NoClassDefFoundError: com/sun/net/httpserver/ExchangeRequestResponse`** in the agent log: the agent's built-in `HttpServer` instrumentation doesn't work on some JVMs. The app avoids depending on it by starting the transaction with `@Trace(dispatcher = true)`.
- **`Sharing is only supported for boot loader classes...` JVM warning:** harmless, and silenced by `-Xshare:off` in `run.sh`.
- **App missing from APM:** check the license key and region, and look for `connected to collector...` in the agent log.
