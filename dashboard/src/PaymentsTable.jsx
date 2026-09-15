import { useMemo } from "react";
import {
  CellStyleModule,
  ClientSideRowModelModule,
  ModuleRegistry,
  NumberFilterModule,
  QuickFilterModule,
  RowAutoHeightModule,
  TextFilterModule,
  TooltipModule,
  ValidationModule,
  colorSchemeDark,
  themeQuartz,
} from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import { DARK, FONT_STACK, LIGHT } from "./theme.js";

// Register only the features this grid uses rather than AllCommunityModule — same reason the
// charts use the basic Plotly bundle. ValidationModule is dev-only; it is what turns a
// missing-module mistake into a readable console error instead of a silently dead feature.
ModuleRegistry.registerModules([
  ClientSideRowModelModule,
  TextFilterModule,
  NumberFilterModule,
  QuickFilterModule,
  CellStyleModule,
  RowAutoHeightModule,
  TooltipModule,
  ...(import.meta.env.DEV ? [ValidationModule] : []),
]);

/** Fraud reasons are persisted as a JSON array string; tolerate a bare string too. */
export function parseReasons(raw) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String) : [String(parsed)];
  } catch {
    return [raw];
  }
}

/**
 * Status tokens (fixed, never themed) rather than categorical series colours — these mean
 * good / in-flight / bad, they are not series identity. good and critical are a red/green
 * pair, the classic CVD collision, so the badge always carries the status WORD too; colour
 * never carries the meaning alone.
 */
const STATUS_COLOR = {
  RECEIVED: "#fab219", // warning — in flight, not yet terminal
  PROCESSED: "#0ca30c", // good
  BLOCKED: "#d03b3b", // critical
};

/** warning sits at 1.79:1 on white, so that badge takes ink rather than white text. */
const STATUS_INK = { RECEIVED: "#0b0b0b" };

function StatusBadge({ value }) {
  if (!value) return "—";
  return (
    <span
      className="badge"
      style={{ background: STATUS_COLOR[value] || "#898781", color: STATUS_INK[value] || "#fff" }}
    >
      {value}
    </span>
  );
}

/** One reason per line. The cell VALUE stays a plain string so sort/filter/search work. */
function ReasonsCell({ data }) {
  const reasons = parseReasons(data.fraudReasons);
  if (reasons.length === 0) return <span className="reason-none">—</span>;
  return (
    <ul className="reason-list">
      {reasons.map((r, i) => <li key={i}>{r}</li>)}
    </ul>
  );
}

/**
 * The table view — the WCAG-clean twin of the charts above, so no value is gated behind a
 * hover tooltip. ag-grid carries the sorting and per-column filtering, plus a quick-filter
 * box that searches the fraud reasoning text.
 */
export default function PaymentsTable({ payments, mode, quickFilter }) {
  const t = mode === "dark" ? DARK : LIGHT;

  // Themed from the same tokens as the charts so the grid is not a visually foreign block.
  const theme = useMemo(() => {
    const base = mode === "dark" ? themeQuartz.withPart(colorSchemeDark) : themeQuartz;
    return base.withParams({
      fontFamily: FONT_STACK,
      backgroundColor: t.surface,
      foregroundColor: t.textPrimary,
      borderColor: t.border,
      headerBackgroundColor: t.page,
      headerTextColor: t.muted,
      headerFontWeight: 600,
      accentColor: t.series1,
      oddRowBackgroundColor: "transparent",
      rowHoverColor: t.page,
      wrapperBorderRadius: "8px",
      fontSize: "13px",
      headerFontSize: "11px",
    });
  }, [mode, t]);

  const columnDefs = useMemo(() => [
    {
      headerName: "Created",
      field: "createdAt",
      width: 105,
      sort: "desc",
      filter: false,
      valueFormatter: (p) =>
        (p.value ? new Date(p.value).toLocaleTimeString([], { hour12: false }) : "—"),
      tooltipValueGetter: (p) => (p.value ? new Date(p.value).toISOString() : ""),
      cellClass: "mono",
    },
    {
      headerName: "ID",
      field: "id",
      width: 100,
      filter: "agTextColumnFilter",
      // Truncated for width; the tooltip and the filter both see the whole UUID.
      valueFormatter: (p) => (p.value ? p.value.slice(0, 8) : "—"),
      tooltipField: "id",
      cellClass: "mono",
    },
    {
      headerName: "Amount",
      field: "amount",
      width: 135,
      type: "rightAligned",
      filter: "agNumberColumnFilter",
      // Sorts and filters as a number; the currency rides along in the formatted text so it
      // does not need a column of its own.
      valueGetter: (p) => (p.data.amount == null ? null : Number(p.data.amount)),
      valueFormatter: (p) => (p.value == null ? "—"
        : `${p.value.toLocaleString(undefined, { minimumFractionDigits: 2 })} ${p.data.currency || ""}`.trim()),
      cellClass: "num",
    },
    { headerName: "Customer", field: "customerId", width: 105, filter: "agTextColumnFilter" },
    { headerName: "Country", field: "country", width: 90, filter: "agTextColumnFilter" },
    {
      headerName: "Status",
      field: "status",
      width: 125,
      filter: "agTextColumnFilter",
      cellRenderer: StatusBadge,
    },
    {
      headerName: "Triage",
      field: "fraudDecision",
      width: 105,
      filter: "agTextColumnFilter",
      valueFormatter: (p) => p.value || "—",
    },
    {
      headerName: "Risk",
      field: "riskScore",
      width: 70,
      type: "rightAligned",
      filter: "agNumberColumnFilter",
      valueGetter: (p) => (p.data.riskScore == null ? null : Number(p.data.riskScore)),
      valueFormatter: (p) => (p.value == null ? "—" : p.value.toFixed(2)),
      cellClass: "num",
    },
    {
      headerName: "Fraud reasoning",
      colId: "fraudReasons",
      flex: 1,
      minWidth: 260,
      filter: "agTextColumnFilter",
      // The value is the flattened text, so sorting, the column filter and the quick filter
      // all operate on the reasoning itself; the renderer only changes how it looks.
      valueGetter: (p) => parseReasons(p.data.fraudReasons).join(" · "),
      cellRenderer: ReasonsCell,
      autoHeight: true,
      wrapText: true,
      sortable: true,
    },
  ], []);

  const defaultColDef = useMemo(() => ({
    sortable: true,
    resizable: true,
    filter: true,
    floatingFilter: true,
    filterParams: { buttons: ["reset"], debounceMs: 200 },
  }), []);

  if (payments.length === 0) {
    return <p className="chart-empty">No payments in this window — submit one above.</p>;
  }

  return (
    <div className="grid-host">
      <AgGridReact
        theme={theme}
        rowData={payments}
        columnDefs={columnDefs}
        defaultColDef={defaultColDef}
        quickFilterText={quickFilter}
        // Stable identity across the 2s poll, so sort/filter/scroll state survives a refresh
        // instead of the grid tearing down and jumping back to the top.
        getRowId={(p) => p.data.id}
        tooltipShowDelay={200}
        rowHeight={34}
        headerHeight={32}
        floatingFiltersHeight={32}
        domLayout="normal"
        suppressCellFocus
      />
    </div>
  );
}
