# SVG Rebuild — Implementation Status

Maps `SVG_REBUILD_SPEC.md` to the implementation in `src/main.cljs`. All rows
below are implemented and exercised by the Playwright suites unless noted.

| Phase | Feature | Status | Where / notes |
| :--- | :--- | :--- | :--- |
| 1 | Native SVG canvas via Reagent/Scittle | Done | `canvas`, `shape-view`; no tldraw at runtime |
| 1 | Coalesced input + midpoint Bézier smoothing | Done | `coalesced`, `add-draw-points`, `path-d` |
| 1 | Pan & zoom (touch / wheel / middle-drag) | Done | `on-down`, `on-wheel`, `start-pinch`, `apply-pinch` |
| 1 | Eraser trail + segment intersection | Done | `eraser-filter`, `seg-int?`, `pt-seg-dist` |
| 1 | CC-29 + named palettes | Done | `palettes`, `swatch`, `pick-color` |
| 1 | Canvas background color | Done | `BG` mode, `pick-color`, per-page palette |
| 2 | Marquee + hit testing | Done | `select-down`, `select-up`, `hit-shape?` |
| 2 | Bounding box move / scale / rotate | Done | `begin-scale`, `begin-rotate`, `selection-overlay` |
| 2 | Duplicate in place | Done | `duplicate!` (`Ctrl-D`) |
| 2 | Undo / redo (bounded) | Done | `history-push!`, `undo!`, `redo!` |
| 3 | Path simplification (`simplify-js`) | Done | `simplify-shape` (`Ctrl-L`) |
| 3 | Open / closed toggle | Done | `toggle-closed!` (appends/removes `Z`) |
| 3 | Solid fill toggle | Done | `toggle-fill!` |
| 3 | Stroke size presets | Done | `size-order`, `set-size!` |
| 4 | Vertex edit mode + handles | Done | `on-dbl`, `vertex-overlay` |
| 4 | Drag vertex / delete vertex / insert vertex | Done | `select-move` `:vertex`, `on-dbl`, `insert-vertex` |
| 5 | Trace image layer (50% opacity) | Done | `load-trace-file!`, `add-trace-image!`, trace row in panel |
| 5 | Pinterest overlay + "Trace on Canvas" | Done | `src/pinterest.cljs` + `window.addTraceImageToCanvas` |
| 5 | Date-based pages (IndexedDB) | Done | `save-page!`, `load-page!`, date input in top bar |
| 5 | SVG export (canvas + selection) | Done | `export-svg!`, `shape->svg` |

## Deviations / follow-ups

- **Pages UI**: the spec suggested a reverse-chronological date *dropdown*; the
  implementation uses a native date picker, which is equivalent and simpler.
- **Trace lock/unlock**: the trace layer is always background-only
  (`pointer-events: none`) with an opacity slider and remove button, rather than
  an explicit lock/unlock mode.
- **Eraser preview**: drawn as a translucent red polyline, as specified.

## Baseline

`tests/baseline-screenshots/01..04-*.png` capture the pre-rebuild tldraw UI.
New renders are captured as `new-*.png` by the test suites.
