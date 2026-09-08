/**
 * The three bottom/top-nav icons, taken directly from the approved mockup's inline SVG
 * paths (orszem-public-ui-concept-v1-corrected.html) - a home glyph, a plus-in-circle for
 * "new report", and a clock-with-arrow for "history". Purely decorative (aria-hidden);
 * the visible label is what nav-link text already provides.
 */
export function HomeIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M3 10.8 12 3l9 7.8v9.1a1.1 1.1 0 0 1-1.1 1.1h-5.2v-6.2H9.3V21H4.1A1.1 1.1 0 0 1 3 19.9z" />
    </svg>
  )
}

export function NewReportIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v8M8 12h8" />
    </svg>
  )
}

export function HistoryIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M4.8 8.2A8 8 0 1 1 4 14" />
      <path d="M4.8 8.2V3.8M4.8 8.2H9" />
      <path d="M12 7.5V12l3.1 1.9" />
    </svg>
  )
}

export function BackIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M15 5 8 12l7 7" />
    </svg>
  )
}

export function CloseIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M6 6l12 12M18 6 6 18" />
    </svg>
  )
}

export function CheckIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M5 13l4 4L19 7" />
    </svg>
  )
}
