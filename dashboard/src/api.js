const GATEWAY = import.meta.env.VITE_GATEWAY_URL || "http://localhost:8080";
const PROMETHEUS = import.meta.env.VITE_PROMETHEUS_URL || "http://localhost:9090";

export { GATEWAY, PROMETHEUS };

/**
 * Newest `limit` payments. The endpoint is paginated (see PaymentController) and returns
 * the full table size in X-Total-Count, so the UI can say how much of the table it is
 * actually showing instead of implying the chart covers everything.
 */
export async function fetchPayments(limit) {
  const res = await fetch(`${GATEWAY}/api/payments?limit=${limit}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const rows = await res.json();
  const header = res.headers.get("X-Total-Count");
  return { rows, total: header == null ? rows.length : Number(header) };
}

export async function submitPayment(body) {
  const res = await fetch(`${GATEWAY}/api/payments`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Gateway returned HTTP ${res.status}`);
  return res.json();
}

/**
 * Instant PromQL query, straight from the browser.
 *
 * Prometheus 2.55 ships with CORS open (`--web.cors.origin` defaults to `.*`), so the
 * dashboard talks to :9090 directly and needs no backend proxy.
 *
 * NOTE this is /api/v1/query, not /api/v1/query_range. A latency histogram is one
 * distribution, not a series over time — `increase(...[window])` at a single instant is
 * already the whole window. query_range would return that same distribution repeated at N
 * timestamps and we would throw away all but one.
 */
export async function promQuery(expr) {
  const res = await fetch(`${PROMETHEUS}/api/v1/query?query=${encodeURIComponent(expr)}`);
  if (!res.ok) throw new Error(`Prometheus HTTP ${res.status}`);
  const body = await res.json();
  if (body.status !== "success") throw new Error(body.error || "Prometheus query failed");
  return body.data.result;
}
