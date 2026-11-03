# SketchThing

A simple drawing web app built on tldraw and Scittle (ClojureScript), with Pinterest reference integration.

## Features

- Canvas drawing using tldraw with customized line conversion:
  - Freehand draw strokes convert to editable line shapes automatically.
  - Simplify line points using `Ctrl-L` or the toolbar button.
  - Edit vertices directly using `Enter` or double-clicking handles.
  - Toggle open/closed path shapes and solid fill.
- Palette selection:
  - Supports CC-29 palette and default tldraw colors.
  - Per-page canvas background color selection.
- Automatic page management:
  - Pages are named by date and default to today's date.
  - Drawings and state persist locally in IndexedDB.
- Pinterest reference overlay:
  - Configure a Pinterest username to load pins and boards.
  - Browse boards or all pins with shuffle mode and keyboard/touch navigation.
  - Service worker caching for pin images.
  - One-click "Trace on Canvas" button to insert reference images directly onto the canvas for tracing.
