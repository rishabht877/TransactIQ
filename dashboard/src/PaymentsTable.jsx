/** Fraud reasons are persisted as a JSON array string; tolerate a bare string too. */
export function parseReasons(raw) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [String(parsed)];
  } catch {
    return [raw];
  }
}

/**
 * Status tokens (fixed, never themed) rather than categorical series colours — these mean
 * good / in-progress / bad, they are not series identity. good and critical are a
 * red/green pair, which is the classic CVD collision, so the badge always carries the
 * status WORD as well; colour never carries the meaning alone.
 */
const STATUS_COLOR = {
  RECEIVED: "#fab219", // warning — in flight, not yet terminal
  PROCESSED: "#0ca30c", // good
  BLOCKED: "#d03b3b", // critical
};

/** warning sits at 1.79:1 on white, so its badge takes ink rather than white text. */
const STATUS_INK = { RECEIVED: "#0b0b0b" };

/**
 * The table view — the WCAG-clean twin of the charts above. Every number plotted is
 * readable here as text, so nothing is gated behind a hover tooltip.
 */
export default function PaymentsTable({ payments }) {
  if (payments.length === 0) {
    return <p className="chart-empty">No payments in this window — submit one above.</p>;
  }

  return (
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
            <td>
              <span
                className="badge"
                style={{
                  background: STATUS_COLOR[p.status] || "#898781",
                  color: STATUS_INK[p.status] || "#fff",
                }}
              >
                {p.status}
              </span>
            </td>
            <td>{p.fraudDecision || "—"}</td>
            <td>{p.riskScore != null ? Number(p.riskScore).toFixed(2) : "—"}</td>
            <td className="reasons">
              <ul>{parseReasons(p.fraudReasons).map((r, i) => <li key={i}>{r}</li>)}</ul>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
