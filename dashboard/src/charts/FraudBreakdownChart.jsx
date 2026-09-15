import { useMemo } from "react";
import { Plot, PLOT_CONFIG, baseLayout, legendBelow } from "./plot.jsx";
import { barWidth, useMeasuredWidth } from "./useMeasuredWidth.js";

// Fixed order, low->high severity. Colour follows the entity, so a decision that drops to
// zero never repaints the others.
const DECISIONS = ["APPROVE", "ESCALATE", "BLOCK"];
const STATUSES = ["PROCESSED", "BLOCKED"];

function tally(payments) {
  const grid = new Map(DECISIONS.map((d) => [d, new Map(STATUSES.map((s) => [s, 0]))]));
  let untriaged = 0;
  for (const p of payments) {
    const decision = p.fraudDecision;
    // A RECEIVED row has no triage result yet — counting it as anything would be a lie.
    if (!decision || !grid.has(decision) || !grid.get(decision).has(p.status)) {
      untriaged += 1;
      continue;
    }
    const row = grid.get(decision);
    row.set(p.status, row.get(p.status) + 1);
  }
  return { grid, untriaged };
}

/**
 * Triage decision (x) against the terminal status it produced (series).
 *
 * The two axes are the point: the processor treats anything that is not APPROVE as a block
 * (PaymentProcessingService), so ESCALATE and BLOCK both land in BLOCKED. Plotting the two
 * separately makes that collapse visible instead of hiding it behind a single status count.
 */
export default function FraudBreakdownChart({ payments, t }) {
  const { grid, untriaged } = useMemo(() => tally(payments), [payments]);
  const [wrapRef, wrapWidth] = useMeasuredWidth();

  const seriesColor = { PROCESSED: t.series1, BLOCKED: t.series2 };
  const triaged = DECISIONS.reduce(
    (sum, d) => sum + STATUSES.reduce((s, st) => s + grid.get(d).get(st), 0), 0);

  if (triaged === 0) {
    return (
      <p className="chart-empty">
        No triaged payments yet{untriaged > 0 ? ` — ${untriaged} not triaged.` : "."}
      </p>
    );
  }

  const data = STATUSES.map((status) => {
    const values = DECISIONS.map((d) => grid.get(d).get(status));
    return {
      type: "bar",
      name: status,
      x: DECISIONS,
      y: values,
      marker: { color: seriesColor[status], cornerradius: 4 },
      width: barWidth(wrapWidth, DECISIONS.length),
      // Value on the cap; zeros stay blank so the chart is not littered with "0".
      text: values.map((v) => (v > 0 ? String(v) : "")),
      textposition: "outside",
      textfont: { color: t.textSecondary, size: 11 },
      cliponaxis: false,
      hovertemplate: `%{x} → ${status}<br>%{y} payments<extra></extra>`,
    };
  });

  return (
    <div ref={wrapRef}>
      <Plot
        data={data}
        layout={baseLayout(t, {
          barmode: "group",
          // Thin bars with air in the slot; the gap is the separator, not a stroke.
          bargap: 0.45,
          bargroupgap: 0.08,
          hovermode: "closest",
          showlegend: true,
          legend: legendBelow(t),
          margin: { l: 56, r: 16, t: 16, b: 64 },
          xaxis: { type: "category" },
          yaxis: { title: { text: "Payments" }, rangemode: "tozero" },
        })}
        config={PLOT_CONFIG}
        style={{ width: "100%", height: "280px" }}
        useResizeHandler
      />
      <p className="chart-note">
        {triaged.toLocaleString()} triaged
        {untriaged > 0 && <> · {untriaged.toLocaleString()} not triaged (no decision on the row)</>}
        . ESCALATE and BLOCK both terminate as BLOCKED — only APPROVE reaches PROCESSED.
      </p>
    </div>
  );
}
