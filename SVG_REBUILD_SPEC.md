# SVG & ClojureScript Rebuild Specification

## 1. Overview and Goals

This document specifies the replacement of the proprietary `tldraw` canvas engine in SketchThing with a custom, pure SVG vector canvas built in ClojureScript (Scittle + Reagent).

### Core Priorities
1. **Concise, minimal line count, and DRY by default:** A fundamental guiding principle is writing as few lines of code as possible while maintaining readability. Factor out repeated patterns, keep helper functions small and idempotent, and eliminate boilerplate. Never write duplicated logic across tools or event handlers.
2. **Unified, idiomatic architecture:** Single source of truth in one ClojureScript state atom driving Reagent SVG components. No competing state machines, mutable external stores, or hidden canvas states.
3. **Drawing feel parity:** Stroke drawing must feel as fluid and natural as tldraw/Excalidraw on high-refresh touch screens and desktop pointers.
4. **Feature parity:** Retain all validated functionality (freehand smoothing, open/closed toggle, fills, CC-29 palette, canvas background color, drag eraser, vertex editing, Pinterest reference trace overlay, date pages, and SVG export).
5. **Phased execution starting with a spike:** Deliver a working Phase 1 spike (drawing, erasing, touch/desktop pan-zoom, palette selection) before proceeding to vertex editing and multi-page persistence.
6. **Baseline visual validation:** Capture reference screenshots of the existing working application using Playwright before replacing code.

---

## 2. Feature and Requirement Matrix

| Feature | Existing tldraw Implementation | SVG Rebuild Target | Phase |
| :--- | :--- | :--- | :--- |
| **Canvas Engine** | `@tldraw/tldraw` SDK (v2 proprietary) | Native SVG DOM rendered via Reagent/Scittle | Phase 1 |
| **Touch Drawing Feel** | High-frequency points, cubic midpoint interpolation | `PointerEvent.getCoalescedEvents()`, midpoint quadratic/cubic Bézier | Phase 1 |
| **Pan & Zoom** | Built-in tldraw camera | SVG root `<g transform="...">`, 2-finger touch, wheel, middle drag | Phase 1 |
| **Eraser** | Drag stroke trail, deletes intersecting shapes | Drag line trail, line-segment bounding intersection, delete hits | Phase 1 |
| **Palette Management** | CC-29 (29 Lospec colors) + tldraw named colors | CC-29 + named swatches, active stroke color selection | Phase 1 |
| **Canvas Background** | Per-page background color from active palette | `style="background-color: ..."` driven by page state atom | Phase 1 |
| **Undo / Redo** | Built-in tldraw history | Bounded immutable vector history stack in state atom (`Ctrl-Z`, `Ctrl-Y`) | Phase 2 |
| **Selection & Transforms** | Select tool, bounding box, drag, resize, rotate | Marquee box + fat stroke pads, bounding box with resize/rotate handles | Phase 2 |
| **Duplicate in Place** | `Ctrl-D` / toolbar button at exact same coordinates | Clone shape map at identical `(x, y)` coordinates | Phase 2 |
| **Path Simplification** | `simplify-js` (`Ctrl-L` / button) | Run `simplify-js` over raw path vertices and rebuild path `d` | Phase 3 |
| **Open / Closed Path Toggle** | Custom line shape util toggle (`isClosed`) | Append/remove `Z` in path data, close start/end vertices | Phase 3 |
| **Fill Toggle** | `solid` vs `none` fill | `fill="currentColor"` or `fill="none"` | Phase 3 |
| **Stroke Sizes** | `s`, `m`, `l`, `xl` presets | Stroke width constants (e.g. 2px, 3.5px, 5px, 10px) scaled by camera | Phase 3 |
| **Vertex / Point Editing** | Double-click / Enter vertex handles | Double-tap/click into vertex mode, drag points, double-tap delete | Phase 4 |
| **Trace Image Layer** | Locked 50% opacity image shape via tldraw asset | Background SVG `<image>` with dedicated "Trace Layer" unlock mode | Phase 5 |
| **Pinterest Integration** | `src/pinterest.cljs` overlay + Trace button | Retain existing Pinterest overlay and pass image to SVG trace layer | Phase 5 |
| **Date-based Pages** | IndexedDB multi-page store (`YYYY-MM-DD`) | IndexedDB store with reverse-chronological date dropdown | Phase 5 |
| **SVG Export** | tldraw SVG export | Clean standalone `.svg` export for selection and whole canvas | Phase 5 |

---

## 3. Phase 0: Baseline Capture Protocol

Before modifying `src/index.html` or `src/main.cljs`, the agent must run a Playwright script against the existing running application (running on `http://localhost:8000`) and save baseline screenshots into `tests/baseline-screenshots/`:

1. `01-initial-canvas.png`: Default canvas with today's date and default background color.
2. `02-style-panel-palette.png`: Style panel expanded showing CC-29 swatch grid.
3. `03-drawn-stroke.png`: A freehand stroke drawn across the canvas.
4. `04-pinterest-overlay.png`: Pinterest viewer overlay opened.

The agent must keep these screenshots as the visual reference standard for layout, palette swatches, and UI positioning.

---

## 4. Phase 1 Spike Specification (Drawing, Erasing, Pan-Zoom, Palette)

### 4.1 State Schema (Single Source of Truth)

All Phase 1 state must reside in a single ClojureScript atom `app-state`:

```clojure
{:tool :draw                  ; :draw | :eraser | :select
 :viewport {:x 0.0            ; Pan X offset in screen pixels
            :y 0.0            ; Pan Y offset in screen pixels
            :zoom 1.0}        ; Zoom scale factor (1.0 = 100%)
 :palette {:current-id "cc-29"
           :active-color "#212123"
           :bg-color "#f2f0e5"}
 :drawing {:active-points []} ; Points currently being drawn: [{:x 10 :y 20} ...]
 :eraser {:active-points []}  ; Trail of active eraser stroke
 :shapes []}                  ; Vector of committed shape maps
```

Each shape map:
```clojure
{:id "shape:12345"
 :type :path
 :points [{:x 10 :y 20} ...]
 :d "M 10 20 Q ..."
 :stroke "#212123"
 :stroke-width 3.5
 :fill "none"
 :is-closed false}
```

### 4.2 Drawing Feel & Smoothing Algorithm

To match tldraw's drawing fidelity:
1. **Pointer Capture:** On `pointerdown`, call `(.setPointerCapture event.target event.pointerId)`.
2. **Coalesced Input:** Collect all intermediate coordinates using `(.getCoalescedEvents event)` when available, falling back to `[event]`.
3. **Coordinate Transformation:** Transform client coordinates `(clientX, clientY)` to SVG world coordinates:
   $$\text{worldX} = \frac{\text{clientX} - \text{viewport.x}}{\text{viewport.zoom}}$$
   $$\text{worldY} = \frac{\text{clientY} - \text{viewport.y}}{\text{viewport.zoom}}$$
4. **Live Midpoint Smoothing:**
   - As points stream in, generate an SVG path `d` string using midpoint quadratic curves:
     - First point: `M p0.x p0.y`
     - Subsequent points: quadratic curve to the midpoint between $p_i$ and $p_{i+1}$, using $p_i$ as the control point:
       $$M = \left(\frac{p_i.x + p_{i+1}.x}{2}, \frac{p_i.y + p_{i+1}.y}{2}\right)$$
       `Q pi.x pi.y Mi.x Mi.y`
     - Last point: straight line `L pn.x pn.y`.
5. **On Release (`pointerup`):**
   - Commit the smoothed path into `:shapes`.
   - Clear `:drawing.active-points`.

### 4.3 Eraser Tool

1. Set active tool to `:eraser`.
2. On `pointerdown` and `pointermove`, record eraser points into `:eraser.active-points`.
3. Render a temporary translucent red eraser trail on top of the SVG canvas.
4. For each segment of the eraser trail $(e_1, e_2)$, check intersection with bounding boxes and line segments of existing shapes in `:shapes`.
5. Remove any shape that intersects the eraser trail.
6. Clear `:eraser.active-points` on `pointerup`.

### 4.4 Viewport Navigation (Touch & Desktop)

1. **Touch Navigation:**
   - Detect multi-touch gestures when `touches.length == 2`.
   - Pinch distance changes adjust `:viewport.zoom`.
   - Center-point drag adjusts `:viewport.x` and `:viewport.y`.
2. **Desktop Navigation:**
   - Wheel scroll with Ctrl/Meta or standard pinch trackpad: zoom centered at mouse pointer coordinate.
   - Middle-click drag: pan canvas offset.
3. **SVG Rendering:**
   - An outer `<svg class="canvas-svg" width="100%" height="100%">`
   - An inner `<g transform="translate(vx, vy) scale(zoom)">` containing all shapes and active drawing paths.

### 4.5 Palette Management

1. Include the full 29-color Lospec CC-29 palette and default named colors.
2. Render swatches in a 5-column grid in the style panel matching existing layout.
3. Support clicking swatches to set active `:palette.active-color`.
4. Support toggling "BG" mode to select the canvas background color from swatches.
5. Canvas background updates immediately via CSS variable or root SVG background fill.

---

## 5. Phase 1 Spike Test Protocol

The agent must validate the Phase 1 spike using a Playwright script `tests/test_spike.js`:

1. **Drawing Test:** Dispatch pointer events (mouse or touch) simulating drawing a stroke; assert that an SVG `<path>` element with non-empty `d` attribute is added to the DOM.
2. **Eraser Test:** Switch tool to eraser, dispatch a pointer stroke crossing the drawn path; assert that the `<path>` element is removed.
3. **Pan/Zoom Test:** Dispatch wheel / touch events; assert that the `<g>` element transform matrix updates.
4. **Palette Test:** Click a CC-29 swatch; assert that subsequent strokes use the selected stroke hex code.
5. **Mobile Touch Test:** Run with a mobile viewport (e.g. 390x844 iPhone profile); verify stroke drawing and 2-finger pan gestures work without browser pinch-to-zoom interference (`touch-action: none`).

---

## 6. Subsequent Phases Roadmap

### Phase 2: Selection & Transformations
- Marquee rectangle selection and click hit-testing with fat invisible stroke pads.
- Selection bounding box with move, scale, and rotate handles.
- Duplicate in place (`Ctrl-D` / button) preserving exact coordinates.
- Undo/redo bounded history stack.

### Phase 3: Path Refinement Tools
- Path simplification via `simplify-js` (`Ctrl-L` / button).
- Open / closed path toggle (`Z` insertion/removal).
- Solid fill toggle (`fill="currentColor"` vs `fill="none"`).
- Stroke thickness presets (`s`, `m`, `l`, `xl`).

### Phase 4: Vertex Editing Mode
- Double-tap/click or button to enter vertex editing mode.
- Render circle handles on each vertex.
- Drag handle to reposition vertex; update path geometry in real time.
- Double-tap handle to delete vertex; tap segment to insert vertex.

### Phase 5: Reference Trace Layer, Pinterest, Pages, and Export
- Trace image layer (locked at 50% opacity in background, unlockable via dedicated UI mode).
- Wire `src/pinterest.cljs` "Trace on Canvas" button to inject into trace image layer.
- Multi-page storage indexed by date (`YYYY-MM-DD`) backed by IndexedDB.
- Clean SVG export for whole canvas and selected shapes.
