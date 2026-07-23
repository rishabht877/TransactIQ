#!/usr/bin/env python3
"""
TransactIQ load test — a multithreaded client that measures gateway acceptance latency +
throughput, then the end-to-end processing drain (throughput + total time) by polling MySQL.

Dependency-free (stdlib only). Requires the gateway running on :8080 and docker-compose MySQL.

Usage:
  scripts/loadtest.py [N] [CONCURRENCY]        # default: 2000 requests, 50 workers
"""
import json
import subprocess
import sys
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

N = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
CONCURRENCY = int(sys.argv[2]) if len(sys.argv) > 2 else 50
GATEWAY = "http://localhost:8080/api/payments"
RUN = f"lt-{int(time.time())}"


def post(i):
    # Unique customer per request so the velocity rule doesn't skew the mix.
    body = json.dumps({
        "amount": 20.00, "currency": "USD", "customerId": f"{RUN}-{i}",
        "cardLast4": "4242", "country": "US", "merchant": "loadtest",
    }).encode()
    req = urllib.request.Request(GATEWAY, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Idempotency-Key": f"{RUN}-{i}",
    })
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            r.read()
            ok = r.status == 202
    except Exception:
        ok = False
    return (time.perf_counter() - t0) * 1000.0, ok  # latency ms, success


def mysql(sql):
    out = subprocess.run(
        ["docker", "exec", "transactiq-mysql", "mysql", "-N", "-utransactiq",
         "-ptransactiq", "transactiq", "-e", sql],
        capture_output=True, text=True)
    return out.stdout.strip()


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    k = min(len(sorted_vals) - 1, int(round(p / 100.0 * (len(sorted_vals) - 1))))
    return sorted_vals[k]


print(f"== Load test: N={N}, concurrency={CONCURRENCY}, run={RUN} ==")

# --- Phase A: fire N requests at the gateway, measure acceptance latency + throughput ---
t_start = time.perf_counter()
lat = []
ok_count = 0
with ThreadPoolExecutor(max_workers=CONCURRENCY) as ex:
    for ms, ok in ex.map(post, range(N)):
        lat.append(ms)
        ok_count += 1 if ok else 0
accept_secs = time.perf_counter() - t_start
lat.sort()

print("\n-- Gateway acceptance (client-measured) --")
print(f"  accepted ok      : {ok_count}/{N}")
print(f"  wall time        : {accept_secs:.2f}s")
print(f"  throughput       : {N / accept_secs:.0f} req/s")
print(f"  latency p50      : {pct(lat, 50):.1f} ms")
print(f"  latency p95      : {pct(lat, 95):.1f} ms")
print(f"  latency p99      : {pct(lat, 99):.1f} ms")
print(f"  latency max      : {lat[-1]:.1f} ms")

# --- Phase B: measure end-to-end processing drain ---
print("\n-- Processing drain (gateway -> Kafka -> processor -> terminal) --")
drain_start = time.perf_counter()
last = 0
deadline = time.time() + 300
while True:
    done = int(mysql(
        f"SELECT COUNT(*) FROM payments WHERE customer_id LIKE '{RUN}-%' "
        f"AND status IN ('PROCESSED','BLOCKED')") or 0)
    if done != last:
        print(f"  {done}/{N} terminal ({time.perf_counter() - drain_start:.0f}s)")
        last = done
    if done >= N or time.time() > deadline:
        break
    time.sleep(2)
drain_secs = time.perf_counter() - drain_start
print(f"  drained {last}/{N} in {drain_secs:.1f}s -> {last / drain_secs:.0f} payments/s processed")

print("\n(For processing p99 + peak consumer lag, check Grafana / "
      "curl localhost:8081/actuator/prometheus | grep transactiq_processing)")
