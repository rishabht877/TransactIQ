import { useCallback, useRef, useState } from "react";

/**
 * Live pixel width of an element.
 *
 * Needed because Plotly sizes bars in AXIS units (a fraction of the category slot) while
 * the mark spec is in pixels — a 4-bin histogram in a 1700px card renders ~320px blocks at
 * Plotly's default width. Converting px -> axis units needs the rendered width.
 *
 * This is a CALLBACK ref, not a useRef + useEffect pair, on purpose: the measured element
 * sits behind the charts' loading/empty early returns, so it mounts after the component
 * does. An effect with a [] dep list runs once against a still-null ref and never
 * re-attaches; a callback ref fires again whenever the node appears.
 */
export function useMeasuredWidth() {
  const [width, setWidth] = useState(0);
  const observer = useRef(null);

  const ref = useCallback((node) => {
    observer.current?.disconnect();
    observer.current = null;
    if (!node) return;
    observer.current = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.current.observe(node);
    setWidth(node.getBoundingClientRect().width);
  }, []);

  return [ref, width];
}

/** Bar thickness capped at `maxPx`, expressed in category-axis units. */
export function barWidth(plotWidthPx, categories, maxPx = 24) {
  if (!plotWidthPx || !categories) return undefined; // let Plotly decide until measured
  // Plot area excludes the axis margins declared in baseLayout.
  const plotArea = Math.max(plotWidthPx - 72, 80);
  return Math.min(0.85, Math.max(0.04, (maxPx * categories) / plotArea));
}
