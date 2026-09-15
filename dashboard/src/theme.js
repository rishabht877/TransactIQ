/**
 * Design tokens for the viz layer.
 *
 * These duplicate the CSS custom properties in styles.css on purpose: Plotly renders to
 * SVG/canvas from a JS layout object and cannot resolve `var(--series-1)`, so the chart
 * layer needs the literal hex. styles.css owns the DOM chrome, this owns the plots — keep
 * the two in sync.
 *
 * Palette provenance: categorical slots 1-2 of the validated default palette. The obvious
 * choice for "approved vs blocked" was the status green/red pair, but that pair fails
 * deuteranopia separation (CVD dE 4.1, well under the 8 target) — red/green is the classic
 * CVD collision. Blue/orange clears every gate in both modes (worst CVD dE 24.7 light /
 * 26.8 dark), so meaning is carried by the axis labels and legend, not by a green/red cue.
 */

export const LIGHT = {
  surface: "#fcfcfb",
  page: "#f9f9f7",
  textPrimary: "#0b0b0b",
  textSecondary: "#52514e",
  muted: "#898781",
  grid: "#e1e0d9",
  axis: "#c3c2b7",
  border: "rgba(11,11,11,0.10)",
  series1: "#2a78d6",
  series2: "#eb6834",
};

export const DARK = {
  surface: "#1a1a19",
  page: "#0d0d0d",
  textPrimary: "#ffffff",
  textSecondary: "#c3c2b7",
  muted: "#898781",
  grid: "#2c2c2a",
  axis: "#383835",
  border: "rgba(255,255,255,0.10)",
  // Same two hues re-stepped for the dark surface — not an automatic flip of the light values.
  series1: "#3987e5",
  series2: "#d95926",
};

export const FONT_STACK = 'system-ui, -apple-system, "Segoe UI", sans-serif';
