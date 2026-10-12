#!/usr/bin/env bash
# Runs java-hybrid-demo with the New Relic Java agent attached.
# Requires: NEW_RELIC_LICENSE_KEY in the environment, agent jar (see NEWRELIC_JAR below), app built with `mvn package`.
set -euo pipefail
cd "$(dirname "$0")"

: "${NEW_RELIC_LICENSE_KEY:?Set NEW_RELIC_LICENSE_KEY before running}"

export NEW_RELIC_APP_NAME="${NEW_RELIC_APP_NAME:-java-hybrid-demo}"
# Let the agent capture spans created through the OpenTelemetry API (agent 9.1.0+)
export NEW_RELIC_OPENTELEMETRY_ENABLED=true
export NEW_RELIC_DISTRIBUTED_TRACING_ENABLED=true
# Prerequisite for the agent's OTel SDK/autoconfigure integration
export OTEL_JAVA_GLOBAL_AUTOCONFIGURE_ENABLED=true

# Locate the agent: $NEWRELIC_JAR, else ./newrelic/newrelic.jar, else ../newrelic/newrelic.jar
AGENT_JAR="${NEWRELIC_JAR:-}"
if [[ -z "$AGENT_JAR" ]]; then
  for candidate in newrelic/newrelic.jar ../newrelic/newrelic.jar; do
    if [[ -f "$candidate" ]]; then AGENT_JAR="$candidate"; break; fi
  done
fi
if [[ -z "$AGENT_JAR" || ! -f "$AGENT_JAR" ]]; then
  echo "newrelic.jar not found. Set NEWRELIC_JAR=/path/to/newrelic.jar" >&2
  exit 1
fi
AGENT_JAR="$(cd "$(dirname "$AGENT_JAR")" && pwd)/$(basename "$AGENT_JAR")"
echo "Using agent: $AGENT_JAR"

exec java -Xshare:off -javaagent:"$AGENT_JAR" -jar demo-app/target/java-hybrid-demo.jar
