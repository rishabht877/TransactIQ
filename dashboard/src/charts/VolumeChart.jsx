import { useMemo } from "react";
import { Plot, PLOT_CONFIG, baseLayout } from "./plot.jsx";

/** Bucket-size ladder — picked so a window renders ~40 buckets rather than one bar per row. */
const LADDER = [1e3, 5e3, 10e3, 30e3, 60e3, 5 * 60e3, 15 * 60e3, 60 * 60e3, 6 * 60 * 60e3, 24 * 60 * 60e3];

function pickBucketMs(spanMs) {
  const ideal = spanMs / 40;
  return LADDER.find((step) => step >= ideal) || LADDER[LADDER.length - 1];
}

function bucketize(payments) {
  const times = payments
    .map((p) => Date.parse(p.createdAt))
    .filter((ms) => !Number.isNaN(ms))
    .sort((a, b) => a - b);
  if (times.length === 0) return { x: [], y: [], bucketMs: 0 };

  const span = Math.max(times[times.length - 1] - times[0], 1000);
  const bucketMs = pickBucketMs(span);
  const start = Math.floor(times[0] / bucketMs) * bucketMs;
  const end = Math.floor(times[times.length - 1] / bucketMs) * bucketMs;

  // Seed every slot so quiet periods draw as zero instead of interpolating across the gap.
  const counts = new Map();
  for (let ts = start; ts <= end; ts += bucketMs) counts.set(ts, 0);
  for (const ms of times) {
    const slot = Math.floor(ms / bucketMs) * bucketMs;
    counts.set(slot, (counts.get(slot) || 0) + 1);
  }

  const slots = [...counts.keys()].sort((a, b) => a - b);
  return { x: slots.map((ts) => new Date(ts)), y: slots.map((ts) => counts.get(ts)), bucketMs };
}

function describeBucket(ms) {
  if (ms >= 60 * 60e3) return `${ms / (60 * 60e3)}h`;
  if (ms >= 60e3) return `${ms / 60e3}m`;
  return `${ms / 1000}s`;
}

/**
 * Transaction volume over time. Single series, so no legend box — the card title says what
 * is plotted. The peak is direct-labelled; the axis and tooltip carry the rest.
 */
export default function VolumeChart({ payments, t }) {
  const { x, y, bucketMs } = useMemo(() => bucketize(payments), [payments]);

  if (x.length === 0) return <p className="chart-empty">No payments in this window.</p>;

  const peak = y.indexOf(Math.max(...y));
  const unit = describeBucket(bucketMs);

  return (
    <>
      <Plot
        data={[{
          type: "scatter",
          mode: "lines",
          x,
          y,
          line: { color: t.series1, width: 2, shape: "linear" },
          fill: "tozeroy",
          // Area fill is a ~10% wash of the series hue, never a saturated block.
          fillcolor: `${t.series1}1a`,
          hovertemplate: `%{y} payments<br>%{x|%H:%M:%S}<extra></extra>`,
        }]}
        layout={baseLayout(t, {
          hovermode: "x unified",
          showlegend: false,
          yaxis: { title: { text: `Payments per ${unit}` }, rangemode: "tozero" },
          margin: { l: 56, r: 16, t: 24, b: 44 },
          xaxis: { type: "date" },
          annotations: y[peak] > 0 ? [{
            x: x[peak],
            y: y[peak],
            text: `peak ${y[peak]}`,
            showarrow: false,
            yshift: 14,
            font: { color: t.textSecondary, size: 11 },
          }] : [],
        })}
        config={PLOT_CONFIG}
        style={{ width: "100%", height: "260px" }}
        useResizeHandler
      />
      <p className="chart-note">
        {payments.length.toLocaleString()} payments, bucketed per {unit}.
      </p>
    </>
  );
}
