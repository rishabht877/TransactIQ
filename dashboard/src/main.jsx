import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App.jsx";
import "./styles.css";

// Apply the stored theme before first paint so the charts never render in the wrong mode
// and then flip. An explicit stamp wins over the OS setting (see styles.css scoping).
const stored = localStorage.getItem("transactiq-theme");
if (stored === "dark" || stored === "light") {
  document.documentElement.setAttribute("data-theme", stored);
}

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
