import { useEffect, useState } from "react";
import { DARK, LIGHT } from "./theme.js";

/** Resolves the active mode from an explicit `data-theme` stamp, else the OS setting. */
function resolveMode() {
  const stamped = document.documentElement.getAttribute("data-theme");
  if (stamped === "dark" || stamped === "light") return stamped;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

/** Current mode + its token set, kept live as the OS setting or the theme stamp changes. */
export function useTheme() {
  const [mode, setMode] = useState(resolveMode);

  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const sync = () => setMode(resolveMode());
    mq.addEventListener("change", sync);
    // The toggle writes data-theme on <html>; watch for it so plots re-theme too.
    const observer = new MutationObserver(sync);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => {
      mq.removeEventListener("change", sync);
      observer.disconnect();
    };
  }, []);

  return { mode, t: mode === "dark" ? DARK : LIGHT };
}
