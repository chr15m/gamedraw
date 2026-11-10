# SketchThing

A small, single-file-ish vector drawing web app. The canvas engine is a custom
**native SVG** renderer written in **ClojureScript** and run in the browser with
[Scittle](https://github.com/babashka/scittle) + [Reagent](https://reagent-project.github.io/).
There is no build step: `src/main.cljs` is evaluated directly by Scittle.

See `SVG_REBUILD_SPEC.md` for the design specification and
`SVG_REBUILD_STATUS.md` for how the implementation maps to it.

## Running

Serve `src/` as static files and open `index.html`:

```bash
python3 -m http.server 8123 --directory src
# -> http://localhost:8123/
```

The app depends only on CDN libraries at runtime (React 18, Scittle, Reagent,
simplify-js, idb-keyval) and has no server component.

## Features

### Canvas & drawing
- Freehand drawing using `PointerEvent.getCoalescedEvents()` for high-frequency
  input, smoothed live with midpoint quadratic Bézier curves.
- Constant-width strokes; single taps produce dots.
- Tools: Draw, Eraser, Flood fill, Select, Pan.
- Flood fill tool creates vector path fills for enclosed interior regions, or sets the canvas background color if clicked outside or uncontained.
- Eraser drags a trail and deletes any stroke whose segments it intersects.
- Astroid / stylus friendly: `touch-action: none`, no zoom-jank while drawing.

### Navigation
- Desktop: middle-mouse drag or Pan tool to pan, `Ctrl`/`Cmd` + wheel (or
  trackpad pinch) to zoom at the cursor, `+`/`−`/`1:1` buttons.
- Touch: two-finger pinch-zoom and pan; single finger draws.

### Style
- CC-29 (29-color Lospec palette) and the default named colors.
- `BG` mode selects the canvas background color; backgrounds are stored per page.
- Stroke sizes `S` / `M` / `L` / `XL` (2 / 3.5 / 5 / 10 px).

### Editing
- Click / marquee selection with a bounding box, corner scale handles, and a
  rotate handle.
- Move, delete, and duplicate-in-place (`Ctrl-D`).
- Simplify selected paths with `simplify-js` (`Ctrl-L`).
- Toggle open/closed paths and solid fills.
- Vertex editing: double-click a shape (or select and double-click), drag the
  red vertex handles to reshape, double-click a handle to delete a vertex, click
  a segment to insert one.
- Undo / redo (`Ctrl-Z` / `Ctrl-Y` / `Ctrl-Shift-Z`), bounded history.

### Pages, persistence, export
- Pages keyed by date (`YYYY-MM-DD`), defaulting to today, persisted to
  IndexedDB (shapes, trace image, palette, background).
- Export the whole canvas or the selection as a standalone `.svg`.

### Reference tracing
- Load any image as a translucent (50% default) trace layer, adjust its opacity
  or remove it.
- Built-in Pinterest reference viewer (`Ref`): configure a username, browse
  boards / all pins in a shuffle or recent order, and send any pin to the canvas
  as a trace image with one click.

## Layout

| File | Purpose |
| --- | --- |
| `src/index.html` | App shell; loads CDN deps and the Scittle sources. |
| `src/main.cljs` | Whole application: state, geometry, events, components. |
| `src/pinterest.cljs` | Pinterest reference viewer (renders into `#pinterest-root`). |
| `src/style.css` | UI styling. |
| `src/legacy/` | The previous tldraw-based implementation, kept for reference. |
| `tests/` | Playwright baseline capture and test suites. |

## Tests

```bash
# baseline screenshots of the *old* tldraw app (needs legacy src serving on :8000)
node tests/capture_baseline.js

# Phase 1 spike protocol
BASE_URL=http://localhost:8123/gamedraw/ node tests/test_spike.js

# Phases 2-5 (selection, path tools, vertices, export, persistence, trace)
BASE_URL=http://localhost:8123/gamedraw/ node tests/test_phases.js
```
