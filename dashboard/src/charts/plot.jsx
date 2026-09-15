import Plotly from "plotly.js-basic-dist-min";
import createPlotlyComponent from "react-plotly.js/factory";
import { FONT_STACK } from "../theme.js";

/**
 * Plotly bound to the BASIC bundle (~1.1MB) rather than the full plotly.js (~4.5MB).
 * The basic bundle ships scatter + bar + pie, which covers every chart here — the latency
 * histogram is pre-bucketed in JS and drawn as `bar`, so we never need the `histogram`
 * trace type (which lives only in the full bundle anyway).
 */
export const Plot = createPlotlyComponent(Plotly);

export const PLOT_CONFIG = { displayModeBar: false, responsive: true };

/**
 * Shared layout: recessive hairline chrome, generous padding, text in ink tokens rather
 * than series colors. Charts override only the axis titles and the bits specific to them.
 */
export function baseLayout(t, overrides = {}) {
  const axis = {
    gridcolor: t.grid,
    linecolor: t.axis,
    zerolinecolor: t.axis,
    tickfont: { color: t.muted, size: 11, family: FONT_STACK },
    titlefont: { color: t.textSecondary, size: 12, family: FONT_STACK },
    automargin: true,
  };
  return {
    autosize: true,
    // Transparent so the card surface shows through in both modes.
    paper_bgcolor: "rgba(0,0,0,0)",
    plot_bgcolor: "rgba(0,0,0,0)",
    font: { family: FONT_STACK, color: t.textSecondary, size: 12 },
    // Room for the x-axis band so tick labels are never cropped by the card.
    margin: { l: 56, r: 16, t: 8, b: 44 },
    hoverlabel: { font: { family: FONT_STACK, size: 12 }, bordercolor: t.border },
    ...overrides,
    xaxis: { ...axis, showgrid: false, ...(overrides.xaxis || {}) },
    yaxis: { ...axis, ...(overrides.yaxis || {}) },
  };
}

/** Legend strip — present whenever a chart draws two or more series. */
export function legendBelow(t) {
  return {
    orientation: "h",
    y: -0.22,
    x: 0,
    font: { color: t.textSecondary, size: 12, family: FONT_STACK },
    bgcolor: "rgba(0,0,0,0)",
  };
}
