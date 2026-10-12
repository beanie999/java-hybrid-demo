#!/usr/bin/env bash
# Runs java-hybrid-demo with the New Relic Java agent attached.
# Requires: NEW_RELIC_LICENSE_KEY in the environment, agent in ./newrelic, app built with `mvn package`.
set -euo pipefail
cd "$(dirname "$0")"

: "${NEW_RELIC_LICENSE_KEY:?Set NEW_RELIC_LICENSE_KEY before running}"

export NEW_RELIC_APP_NAME="${NEW_RELIC_APP_NAME:-java-hybrid-demo}"
# Let the agent capture spans created through the OpenTelemetry API (agent 9.1.0+)
export NEW_RELIC_OPENTELEMETRY_ENABLED=true
export NEW_RELIC_DISTRIBUTED_TRACING_ENABLED=true

exec java -javaagent:newrelic/newrelic.jar -jar demo-app/target/java-hybrid-demo.jar
