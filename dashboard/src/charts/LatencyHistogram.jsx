import { useEffect, useMemo, useState } from "react";
import { PROMETHEUS, promQuery } from "../api.js";
import { binLabel, formatDuration, parseCumulative, quantile, toHistogram, totalCount }
  from "../latency.js";
import { Plot, PLOT_CONFIG, baseLayout } from "./plot.jsx";
import { barWidth, useMeasuredWidth } from "./useMeasuredWidth.js";

export const TIMERS = [
  {
    id: "gateway",
    label: "Gateway accept",
    metric: "transactiq_gateway_create_seconds_bucket",
    blurb: "POST /api/payments — the synchronous part the caller waits on.",
  },
  {
    id: "processing",
    label: "Processor end-to-end",
    metric: "transactiq_processing_seconds_bucket",
    blurb: "Kafka consume → fraud triage → terminal status, per event.",
  },
];

function StatTile({ label, value }) {
  return (
    <div className="tile">
      <span className="tile-label">{label}</span>
      <span className="tile-value">{value}</span>
    </div>
  );
}

/**
 * Bucketed latency distribution, read straight off Prometheus from the browser.
 *
 * This is a real histogram of the exported `le` buckets, not a p50/p99 line — Micrometer's
 * `percentiles-histogram` is enabled for both timers (see each service's application.yml),
 * so the bucket series exist to be differenced. The quantile tiles are computed from those
 * same buckets by the same interpolation Prometheus uses, so they agree with Grafana.
 */
export default function LatencyHistogram({ promWindow, timerId, t }) {
  const timer = TIMERS.find((x) => x.id === timerId) || TIMERS[0];
  const [state, setState] = useState({ cumulative: null, error: null, loading: true });
  const [plotRef, plotWidth] = useMeasuredWidth();

  useEffect(() => {
    let live = true;
    const expr = `sum by (le) (increase(${timer.metric}[${promWindow}]))`;

    async function load() {
      try {
        const result = await promQuery(expr);
        if (live) setState({ cumulative: parseCumulative(result), error: null, loading: false });
      } catch (e) {
        if (live) setState((prev) => ({ ...prev, error: e.message, loading: false }));
      }
    }

    load();
    const id = setInterval(load, 5000);
    return () => { live = false; clearInterval(id); };
  }, [timer.metric, promWindow]);

  const bins = useMemo(
    () => (state.cumulative ? toHistogram(state.cumulative, 12) : []), [state.cumulative]);

  if (state.error) {
    return (
      <div className="chart-empty">
        <p>Cannot reach Prometheus at {PROMETHEUS} ({state.error}).</p>
        <p className="chart-note">
          This panel reads histogram buckets directly from Prometheus, which only runs under
          docker-compose — it is not part of the Helm chart, so the panel stays empty on
          Kubernetes.
        </p>
      </div>
    );
  }

  if (state.loading) return <p className="chart-empty">Querying Prometheus…</p>;

  const observations = totalCount(state.cumulative);
  if (observations === 0 || bins.length === 0) {
    return (
      <div className="chart-empty">
        <p>No {timer.label.toLowerCase()} observations in the last {promWindow}.</p>
        <p className="chart-note">Submit a payment (or run scripts/loadtest.py) and it will fill in.</p>
      </div>
    );
  }

  return (
    <div>
      <div className="tiles">
        <StatTile label="p50" value={formatDuration(quantile(state.cumulative, 0.5))} />
        <StatTile label="p95" value={formatDuration(quantile(state.cumulative, 0.95))} />
        <StatTile label="p99" value={formatDuration(quantile(state.cumulative, 0.99))} />
        <StatTile label="Observations" value={Math.round(observations).toLocaleString()} />
      </div>

      {/*
        The card is full-width because it carries the tiles and the timer control, but a
        4-bin histogram stretched across it strands thin bars in empty space. Size the PLOT
        to its bin count so the geometry holds at any number of bins.
      */}
      <div
        ref={plotRef}
        style={{ maxWidth: `${Math.max(440, bins.length * 72)}px` }}
      >
        <Plot
          data={[{
            type: "bar",
            x: bins.map(binLabel),
            y: bins.map((b) => b.count),
            marker: { color: t.series1, cornerradius: 4 },
            width: barWidth(plotWidth, bins.length),
            hovertemplate: "%{x}<br>%{y:.0f} observations<extra></extra>",
          }]}
          layout={baseLayout(t, {
            showlegend: false,
            hovermode: "closest",
            margin: { l: 56, r: 16, t: 8, b: 76 },
            xaxis: { type: "category", tickangle: -35 },
            yaxis: { title: { text: "Observations" }, rangemode: "tozero" },
          })}
          config={PLOT_CONFIG}
          style={{ width: "100%", height: "280px" }}
          useResizeHandler
        />
      </div>

      <p className="chart-note">
        {timer.blurb} Buckets are cumulative in Prometheus and differenced here;
        {" "}{bins.length} bins over the last {promWindow}. Prometheus runs under
        docker-compose only.
      </p>
    </div>
  );
}
