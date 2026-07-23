import { useEffect, useState } from "react";

const GATEWAY = import.meta.env.VITE_GATEWAY_URL || "http://localhost:8080";
const GRAFANA = import.meta.env.VITE_GRAFANA_URL || "http://localhost:3000";

const STATUS_COLOR = {
  RECEIVED: "#b58900",
  PROCESSED: "#2aa198",
  BLOCKED: "#dc322f",
};

function parseReasons(raw) {
  if (!raw) return [];
  try {
    return JSON.parse(raw);
  } catch {
    return [raw];
  }
}

export default function App() {
  const [payments, setPayments] = useState([]);
  const [error, setError] = useState(null);
  const [form, setForm] = useState({
    amount: "49.99",
    currency: "USD",
    customerId: "cust-1",
    cardLast4: "4242",
    country: "US",
    merchant: "Acme",
  });
  const [submitting, setSubmitting] = useState(false);

  async function refresh() {
    try {
      const res = await fetch(`${GATEWAY}/api/payments`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      // newest first
      data.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
      setPayments(data);
      setError(null);
    } catch (e) {
      setError(`Cannot reach gateway at ${GATEWAY} (${e.message})`);
    }
  }

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 2000); // live-ish polling
    return () => clearInterval(t);
  }, []);

  async function submit(e) {
    e.preventDefault();
    setSubmitting(true);
    try {
      await fetch(`${GATEWAY}/api/payments`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
        },
        body: JSON.stringify({ ...form, amount: Number(form.amount) }),
      });
      await refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  }

  const field = (name) => ({
    value: form[name],
    onChange: (e) => setForm({ ...form, [name]: e.target.value }),
  });

  return (
    <div className="wrap">
      <header>
        <h1>TransactIQ</h1>
        <a className="grafana" href={GRAFANA} target="_blank" rel="noreferrer">
          Metrics (Grafana) ↗
        </a>
      </header>

      <section className="card">
        <h2>Submit a payment</h2>
        <form onSubmit={submit} className="form">
          <label>Amount<input {...field("amount")} type="number" step="0.01" /></label>
          <label>Currency<input {...field("currency")} maxLength={3} /></label>
          <label>Customer<input {...field("customerId")} /></label>
          <label>Card last4<input {...field("cardLast4")} maxLength={4} /></label>
          <label>Country<input {...field("country")} maxLength={2} /></label>
          <label>Merchant<input {...field("merchant")} /></label>
          <button disabled={submitting}>{submitting ? "Submitting…" : "Pay"}</button>
        </form>
        <p className="hint">
          Tip: send a large amount (e.g. 30000) or the same customer many times quickly to see
          the fraud triage BLOCK it.
        </p>
      </section>

      {error && <div className="error">{error}</div>}

      <section className="card">
        <h2>Payments (live)</h2>
        <table>
          <thead>
            <tr>
              <th>ID</th><th>Amount</th><th>Customer</th><th>Status</th>
              <th>Fraud</th><th>Risk</th><th>Reasoning</th>
            </tr>
          </thead>
          <tbody>
            {payments.map((p) => (
              <tr key={p.id}>
                <td className="mono">{p.id.slice(0, 8)}</td>
                <td>{p.amount} {p.currency}</td>
                <td>{p.customerId}</td>
                <td><span className="badge" style={{ background: STATUS_COLOR[p.status] || "#657b83" }}>{p.status}</span></td>
                <td>{p.fraudDecision || "—"}</td>
                <td>{p.riskScore != null ? Number(p.riskScore).toFixed(2) : "—"}</td>
                <td className="reasons">
                  <ul>{parseReasons(p.fraudReasons).map((r, i) => <li key={i}>{r}</li>)}</ul>
                </td>
              </tr>
            ))}
            {payments.length === 0 && (
              <tr><td colSpan={7} className="empty">No payments yet — submit one above.</td></tr>
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}
