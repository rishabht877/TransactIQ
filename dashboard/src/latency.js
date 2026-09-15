/**
 * Turning Prometheus histogram buckets into a drawable distribution.
 *
 * Micrometer's `percentiles-histogram` exports CUMULATIVE buckets: each `le` series counts
 * every observation at or below that bound, and it emits ~70 of them. Neither shape is
 * directly plottable — this module differences them into per-bucket counts and merges the
 * result down to a readable number of bins.
 */

/** Sorted cumulative [{ le, count }] from a Prometheus `sum by (le) (...)` vector. */
export function parseCumulative(result) {
  const points = result
    .map((r) => ({
      le: r.metric.le === "+Inf" ? Infinity : Number(r.metric.le),
      count: Number(r.value[1]),
    }))
    .filter((p) => Number.isFinite(p.count) && !Number.isNaN(p.le))
    .sort((a, b) => a.le - b.le);

  // `increase()` extrapolates at the window edges and can emit a slightly lower value for a
  // wider bound. Cumulative counts must be monotonic, so clamp rather than render a
  // negative bar.
  let running = 0;
  return points.map((p) => {
    running = Math.max(running, p.count);
    return { le: p.le, count: running };
  });
}

/** Total observations in the window (the +Inf bucket). */
export function totalCount(cumulative) {
  return cumulative.length === 0 ? 0 : cumulative[cumulative.length - 1].count;
}

/**
 * Per-bucket counts, trimmed to the occupied range and merged to at most `maxBins`.
 * Returns [{ lo, hi, count }] with bounds in seconds (`hi` may be Infinity).
 */
export function toHistogram(cumulative, maxBins = 12) {
  const bins = [];
  let prevLe = 0;
  let prevCount = 0;
  for (const p of cumulative) {
    bins.push({ lo: prevLe, hi: p.le, count: p.count - prevCount });
    prevLe = p.le;
    prevCount = p.count;
  }

  // Drop the empty head and tail so the plot spans where the data actually is.
  const first = bins.findIndex((b) => b.count > 0);
  if (first === -1) return [];
  let last = bins.length - 1;
  while (last > first && bins[last].count === 0) last -= 1;
  const occupied = bins.slice(first, last + 1);

  const group = Math.ceil(occupied.length / maxBins);
  if (group <= 1) return occupied;

  const merged = [];
  for (let i = 0; i < occupied.length; i += group) {
    const chunk = occupied.slice(i, i + group);
    merged.push({
      lo: chunk[0].lo,
      hi: chunk[chunk.length - 1].hi,
      count: chunk.reduce((sum, b) => sum + b.count, 0),
    });
  }
  return merged;
}

/**
 * Quantile by linear interpolation inside the containing bucket — the same method
 * Prometheus' own `histogram_quantile` uses, so these agree with the Grafana panels.
 */
export function quantile(cumulative, q) {
  const total = totalCount(cumulative);
  if (total === 0) return null;
  const target = q * total;
  let prevLe = 0;
  let prevCount = 0;
  for (const p of cumulative) {
    if (p.count >= target) {
      if (!Number.isFinite(p.le)) return prevLe; // fell in +Inf — report the last finite bound
      const span = p.count - prevCount;
      if (span <= 0) return p.le;
      return prevLe + (p.le - prevLe) * ((target - prevCount) / span);
    }
    prevLe = p.le;
    prevCount = p.count;
  }
  return prevLe;
}

/** Compact duration for axis ticks and stat tiles. */
export function formatDuration(seconds) {
  if (seconds == null) return "—";
  if (!Number.isFinite(seconds)) return "∞";
  if (seconds < 0.001) return `${Math.round(seconds * 1e6)}µs`;
  if (seconds < 1) return `${(seconds * 1000).toFixed(seconds < 0.01 ? 1 : 0)}ms`;
  return `${seconds.toFixed(2)}s`;
}

/** Axis label for a bin, e.g. "5-10ms". The head bin reads as "<= x". */
export function binLabel(bin) {
  if (!Number.isFinite(bin.hi)) return `> ${formatDuration(bin.lo)}`;
  if (bin.lo === 0) return `≤ ${formatDuration(bin.hi)}`;
  return `${formatDuration(bin.lo)}–${formatDuration(bin.hi)}`;
}
