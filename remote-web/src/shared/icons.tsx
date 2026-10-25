const paths = {
  back: 'M9 6L4 11l5 5M4 11h10a5 5 0 0 1 0 10h-2',
  home: 'M4 11l8-7 8 7v9h-5v-6H9v6H4z',
  playpause: 'M5 5v14l8-7zM16 5v14M20 5v14',
  play: 'M7 5v14l11-7z',
  pause: 'M7 5v14M17 5v14',
  mic: 'M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3',
  rewind: 'M4 12a8 8 0 1 0 2.3-5.6M4 4v4h4',
  forward: 'M20 12a8 8 0 1 1-2.3-5.6M20 4v4h-4',
  previous: 'M6 5v14M19 5L9 12l10 7z',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68 1.65 1.65 0 0 0 10 3.17V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
  next: 'M18 5v14M5 5l10 7-10 7z',
  moon: 'M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z',
  search: 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14M20 20l-4.2-4.2',
  grab: 'M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M8 12h8',
  layouts: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  close: 'M6 6l12 12M18 6L6 18',
  up: 'M6 15l6-6 6 6',
  send: 'M5 12h14M13 6l6 6-6 6',
  link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
  shelf: 'M4 7h16M4 12h16M4 17h10',
  pen: 'M4 20l4-1 11-11-3-3L5 16zM14 6l3 3',
  erase: 'M21 5H9l-6 7 6 7h12zM12 9l6 6M18 9l-6 6',
  eraser: 'M8 20h12M4.5 15.5l9-9a2 2 0 0 1 2.8 0l2.2 2.2a2 2 0 0 1 0 2.8L11 19H8z',
  arrow: 'M5 19L19 5M10 5h9v9',
  rect: 'M4 6h16v12H4z',
  ellipse: 'M12 5c4.4 0 8 3.1 8 7s-3.6 7-8 7-8-3.1-8-7 3.6-7 8-7z',
  bell: 'M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4',
  undo:'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5',
  dpad: 'M9 3h6v6h6v6h-6v6H9v-6H3V9h6z',
  pad: 'M4 5h16v14H4zM9 15h6',
} as const

export type IconName = keyof typeof paths

// Always sized: Safari collapses an svg with only a viewBox to nothing inside a flex button.
// A CSS width/height on the svg still overrides these attributes.
export function Icon({ name, size = 22 }: { name: IconName; size?: number }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" width={size} height={size}>
      <path d={paths[name]} />
    </svg>
  )
}
