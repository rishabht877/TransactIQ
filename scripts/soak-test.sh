#!/usr/bin/env bash
#
# Phase 2 soak test: prove the pipeline loses nothing and duplicates nothing across a crash.
#
# What it does:
#   1. Starts the payment-processor.
#   2. Fires N payments at the gateway (each with a unique Idempotency-Key).
#   3. Mid-run, SIGKILLs the processor (simulating a crash), then restarts it.
#   4. Waits for every payment to reach a terminal state, then asserts from MySQL:
#        - no loss:        every accepted payment ends PROCESSED/BLOCKED
#        - no duplicates:  each payment produced exactly ONE outcome event
#
# Requires: docker compose infra up (MySQL 3307, Kafka 9092), and the payment-gateway running
# on :8080. The gateway is NOT killed (it is the durable front door); the processor is.
#
# Usage: scripts/soak-test.sh [N]      (default N=200)
set -euo pipefail

N="${1:-200}"
KILL_AFTER=$(( N * 40 / 100 ))                 # crash the processor after ~40% are sent
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="soak-$(date +%s)"                         # tags this run's rows via customer_id
PROC_LOG="$(mktemp -t transactiq-soak-proc.XXXXXX)"

mysql_q() { docker exec transactiq-mysql mysql -N -utransactiq -ptransactiq transactiq -e "$1" 2>/dev/null; }

wait_health() {  # $1=url $2=name
  for _ in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)" = "200" ] && return 0
    sleep 2
  done
  echo "ERROR: $2 did not become healthy" >&2; return 1
}

start_processor() {
  ( cd "$REPO_DIR" && nohup ./gradlew :services:payment-processor:bootRun >>"$PROC_LOG" 2>&1 & )
  wait_health "http://localhost:8081/actuator/health" "payment-processor"
}

kill_processor() {  # SIGKILL the app JVM (a real crash, not a graceful shutdown)
  pkill -9 -f "PaymentProcessorApplication" 2>/dev/null || true
  pkill -9 -f "payment-processor:bootRun" 2>/dev/null || true
  sleep 3
}

post_payment() {  # $1=index
  curl -s -o /dev/null -X POST "$GATEWAY/api/payments" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${RUN}-$1" \
    -d "{\"amount\":10.00,\"currency\":\"USD\",\"customerId\":\"${RUN}\",\"cardLast4\":\"4242\",\"country\":\"US\",\"merchant\":\"Soak\"}"
}

echo "== Soak test: N=$N, crash after $KILL_AFTER, run tag=$RUN =="
echo "-- verifying gateway is up ($GATEWAY)"
wait_health "$GATEWAY/actuator/health" "payment-gateway"

echo "-- starting processor"
start_processor

echo "-- firing $N payments (killing processor at $KILL_AFTER)"
for i in $(seq 1 "$N"); do
  post_payment "$i"
  if [ "$i" -eq "$KILL_AFTER" ]; then
    echo "   >> SIGKILL processor mid-run (after $i sent)"
    kill_processor
    echo "   >> restarting processor"
    start_processor
  fi
done

echo "-- waiting for all $N payments to reach a terminal state"
DEADLINE=$(( $(date +%s) + 120 ))
while :; do
  TERMINAL=$(mysql_q "SELECT COUNT(*) FROM payments WHERE customer_id='$RUN' AND status IN ('PROCESSED','BLOCKED');")
  [ "${TERMINAL:-0}" -ge "$N" ] && break
  [ "$(date +%s)" -ge "$DEADLINE" ] && { echo "   timed out at $TERMINAL/$N terminal"; break; }
  sleep 2
done

echo "-- asserting"
CREATED=$(mysql_q "SELECT COUNT(*) FROM payments WHERE customer_id='$RUN';")
TERMINAL=$(mysql_q "SELECT COUNT(*) FROM payments WHERE customer_id='$RUN' AND status IN ('PROCESSED','BLOCKED');")
OUTCOMES=$(mysql_q "SELECT COUNT(*) FROM outbox o JOIN payments p ON o.aggregate_id=p.id WHERE p.customer_id='$RUN' AND o.topic IN ('payments.processed','payments.blocked');")
DUPES=$(mysql_q "SELECT COUNT(*) FROM (SELECT o.aggregate_id FROM outbox o JOIN payments p ON o.aggregate_id=p.id WHERE p.customer_id='$RUN' AND o.topic IN ('payments.processed','payments.blocked') GROUP BY o.aggregate_id HAVING COUNT(*)>1) d;")

echo "   created=$CREATED  terminal=$TERMINAL  outcome_events=$OUTCOMES  duplicated_payments=$DUPES"

FAIL=0
[ "$CREATED" -eq "$N" ]      || { echo "FAIL: created ($CREATED) != N ($N) — request lost at gateway"; FAIL=1; }
[ "$TERMINAL" -eq "$N" ]     || { echo "FAIL: terminal ($TERMINAL) != N ($N) — payment lost (not processed)"; FAIL=1; }
[ "$OUTCOMES" -eq "$N" ]     || { echo "FAIL: outcome events ($OUTCOMES) != N ($N)"; FAIL=1; }
[ "$DUPES" -eq 0 ]           || { echo "FAIL: $DUPES payment(s) processed more than once — DUPLICATE effect"; FAIL=1; }

if [ "$FAIL" -eq 0 ]; then
  echo "== PASS: zero loss, zero duplicates across a processor crash =="
else
  echo "== SOAK TEST FAILED =="
fi
echo "(processor log: $PROC_LOG)"
exit "$FAIL"
