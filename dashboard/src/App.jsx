import { useCallback, useEffect, useMemo, useState } from "react";
import { GATEWAY, fetchPayments, submitPayment } from "./api.js";
import FraudBreakdownChart from "./charts/FraudBreakdownChart.jsx";
import LatencyHistogram, { TIMERS } from "./charts/LatencyHistogram.jsx";
import VolumeChart from "./charts/VolumeChart.jsx";
import PaymentsTable from "./PaymentsTable.jsx";
import { useTheme } from "./useTheme.js";

const GRAFANA = import.meta.env.VITE_GRAFANA_URL || "http://localhost:3000";

// `promWindow` is the PromQL range for the latency panel. "All" has no bounded equivalent
// there, so it falls back to 1h rather than silently querying something else.
const WINDOWS = [
  { id: "5m", label: "5 min", ms: 5 * 60e3, promWindow: "5m" },
  { id: "1h", label: "1 hour", ms: 60 * 60e3, promWindow: "1h" },
  { id: "24h", label: "24 hours", ms: 24 * 60 * 60e3, promWindow: "24h" },
  { id: "all", label: "All loaded", ms: null, promWindow: "1h" },
];

// Defaults to 500, not 100: the gateway accepts ~600/s, so the newest 100 rows can span
// under a second and the volume chart degenerates into a single spike. Still bounded — the
// point of paginating was to stop the poll dragging the whole table.
const ROW_LIMITS = [100, 500, 1000];
const DEFAULT_LIMIT = 500;

function ThemeToggle({ mode }) {
  const flip = () => {
    const next = mode === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    localStorage.setItem("transactiq-theme", next);
  };
  return (
    <button className="ghost" onClick={flip} aria-label="Toggle colour scheme">
      {mode === "dark" ? "Light" : "Dark"} mode
    </button>
  );
}

export default function App() {
  const { mode, t } = useTheme();

  const [windowId, setWindowId] = useState("1h");
  const [limit, setLimit] = useState(DEFAULT_LIMIT);
  const [timerId, setTimerId] = useState(TIMERS[0].id);
  const [tableSearch, setTableSearch] = useState("");

  const [payments, setPayments] = useState([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState(null);

  const [form, setForm] = useState({
    amount: "49.99", currency: "USD", customerId: "cust-1",
    cardLast4: "4242", country: "US", merchant: "Acme",
  });
  const [submitting, setSubmitting] = useState(false);

  const activeWindow = WINDOWS.find((w) => w.id === windowId) || WINDOWS[1];

  const refresh = useCallback(async () => {
    try {
      const { rows, total: count } = await fetchPayments(limit);
      setPayments(rows);
      setTotal(count);
      setError(null);
    } catch (e) {
      // Keep the last good render on screen and explain it, rather than blanking the charts.
      setError(`Cannot reach gateway at ${GATEWAY} (${e.message})`);
    }
  }, [limit]);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 2000);
    return () => clearInterval(id);
  }, [refresh]);

  // One window filter scoping every chart, applied to the newest `limit` rows the API returned.
  const scoped = useMemo(() => {
    if (activeWindow.ms == null) return payments;
    const floor = Date.now() - activeWindow.ms;
    return payments.filter((p) => {
      const ms = Date.parse(p.createdAt);
      return Number.isNaN(ms) ? true : ms >= floor;
    });
  }, [payments, activeWindow]);

  async function submit(e) {
    e.preventDefault();
    setSubmitting(true);
    try {
      await submitPayment({ ...form, amount: Number(form.amount) });
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

  const field = (name) => ({
    value: form[name],
    onChange: (e) => setForm({ ...form, [name]: e.target.value }),
  });

  return (
    <div className="wrap viz-root">
      <header>
        <h1>TransactIQ</h1>
        <div className="header-actions">
          <ThemeToggle mode={mode} />
          <a className="ghost" href={GRAFANA} target="_blank" rel="noreferrer">Grafana ↗</a>
        </div>
      </header>

      {/* One filter row above everything it scopes — never a filter inside a chart card. */}
      <div className="filters" role="group" aria-label="Dashboard filters">
        <label>
          Window
          <select value={windowId} onChange={(e) => setWindowId(e.target.value)}>
            {WINDOWS.map((w) => <option key={w.id} value={w.id}>{w.label}</option>)}
          </select>
        </label>
        <label>
          Rows loaded
          <select value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
            {ROW_LIMITS.map((n) => <option key={n} value={n}>newest {n}</option>)}
          </select>
        </label>
        <span className="filter-note">
          Showing {scoped.length.toLocaleString()} of {payments.length.toLocaleString()} loaded
          {total > payments.length && <> · {total.toLocaleString()} in the table</>}
        </span>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="grid">
        <section className="card">
          <h2>Transaction volume</h2>
          <p className="card-sub">Payments accepted over time, from each row's createdAt.</p>
          <VolumeChart payments={scoped} t={t} />
        </section>

        <section className="card">
          <h2>Fraud triage → outcome</h2>
          <p className="card-sub">Which triage decision produced which terminal status.</p>
          <FraudBreakdownChart payments={scoped} t={t} />
        </section>

        <section className="card wide">
          <div className="card-head">
            <div>
              <h2>Latency distribution</h2>
              <p className="card-sub">Prometheus histogram buckets, differenced into bins.</p>
            </div>
            <div className="segmented" role="group" aria-label="Timer">
              {TIMERS.map((timer) => (
                <button
                  key={timer.id}
                  className={timer.id === timerId ? "on" : ""}
                  aria-pressed={timer.id === timerId}
                  onClick={() => setTimerId(timer.id)}
                >
                  {timer.label}
                </button>
              ))}
            </div>
          </div>
          <LatencyHistogram promWindow={activeWindow.promWindow} timerId={timerId} t={t} />
        </section>
      </div>

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

      <section className="card">
        <div className="card-head">
          <div>
            <h2>Payments</h2>
            <p className="card-sub">
              The table view — every value plotted above is readable here, sortable and
              filterable. Column headers sort; the row under each header filters.
            </p>
          </div>
          {/*
            This box scopes the grid only (not the charts), so it belongs with the grid
            rather than in the dashboard filter row above.
          */}
          <input
            className="search"
            type="search"
            value={tableSearch}
            onChange={(e) => setTableSearch(e.target.value)}
            placeholder="Search all columns, incl. reasoning…"
            aria-label="Search payments"
          />
        </div>
        <PaymentsTable payments={scoped} mode={mode} quickFilter={tableSearch} />
      </section>
    </div>
  );
}
