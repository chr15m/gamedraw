# Specification: Vector Sprite Editor

## 1. Overview

This document specifies a web application to draw vector game sprites by hand.
The system uses Excalidraw as the rendering canvas and geometry engine.

## 2. Core Functional Requirements

### 2.1 Drawing Input and Conversion

- The active drawing tool must remain locked to `freedraw`.
- When the pointer goes up (`pointerup`), the system must find new `freedraw` elements.
- The system must simplify raw stroke points with the Ramer-Douglas-Peucker algorithm.
- The system must convert each simplified stroke into a single `line` element:
  - Set `roughness` to 0 for a clean stroke.
  - Set `roundness` to type 2 for a smooth curve.
  - Set `strokeWidth` to the active stroke thickness.
  - Tag element with `customData.converted = true`.
- Proximity closure detection:
  - When pointer goes up, compute distance between start point and end point of the stroke.
  - If distance is within threshold (e.g. 16px) or stroke segment has `isClosed: true`:
    - Mark line as closed (`props.isClosed = true`).
    - Snap end point coordinate to exactly equal the start point coordinate.
    - Set active fill style (`props.fill`) from current editor fill state.
  - Otherwise, leave line as open (`props.isClosed = false`, `props.fill = "none"`).
- Extended `line` shape utility (`CustomLineShapeUtil`):
  - Extend schema props to include `fill` (`DefaultFillStyle`) and `isClosed` (boolean).
  - Override `getGeometry(shape)`:
    - If `shape.props.isClosed` is true, produce closed geometry (`isClosed = true`, `isFilled = fill !== "none"`).
    - Ensure hit-testing, selection bounds, and pointer interactions respect filled interior.
  - Override `component(shape)` and `toSvg(shape)`:
    - Render filled path with closing `Z` instruction when `isClosed` is true.
    - Render fill color and stroke respecting active styles.
- Open / Close toggle control:
  - Add toolbar button / action to toggle shape closure on selected `line` shapes.
  - When closing:
    - Set `props.isClosed = true`.
    - Snap or insert end point matching start point.
    - Retain previous fill value (defaults to `none` if previously unfilled).
  - When opening:
    - Set `props.isClosed = false`.
    - Adjust terminal point if identical to start point.
    - Retain previous fill value.
- The system must replace the original `draw` shape with the converted `line` shape.

### 2.2 Sprite Grid Guide

- The grid guide must show sprite slots starting at coordinate (0, 0).
- The grid guide must not show slots outside the specified column and row boundaries.
- The empty area outside the grid serves as a scratchpad for sprite variations.
- Grid lines must be Excalidraw `line` elements:
  - Set `locked` to true.
  - Set opacity low (20% to 30%).
  - Set `customData.role = "grid"`.
  - Position grid lines at the start of the scene element array (lowest z-order).
- The user can toggle grid visibility.
- The system must omit grid lines during export.

### 2.3 Page Management

- Each page represents one project.
- The system maintains a page registry with:
  - Page ID and page name.
  - Element array (`ExcalidrawElement[]`).
  - Binary files map (`BinaryFiles`).
  - Color palette (`string[]`).
  - Sprite dimensions in pixels (width, height).
  - Grid cell count (columns, rows).
- Page transitions must snapshot the active scene and load the target page into the canvas.

### 2.4 Color Palette

- The user can import color palettes as plain text (one hex code per line).
- The system parses 6-digit hex values.
- Selected palette colors apply to:
  - Active stroke color (`currentItemStrokeColor`).
  - Active fill color (`currentItemBackgroundColor`).
  - Selected elements on the canvas.

### 2.5 Tracing Underlay (Onion Skin)

- The user can load reference images into the scene.
- The system inserts the image as a locked element:
  - Set `locked` to true.
  - Set opacity low (20% to 40%).
  - Set `customData.role = "trace"`.
  - Position the element below drawing strokes and above the grid guide.
- The system must omit tracing elements during export.

### 2.6 Element Grouping and Hierarchy

- A sprite is defined by a `frame` container with a unique name.
- Sub-components (for example: head, arm) use Excalidraw `groupIds` and `customData.layer`.
- Group names and sprite names must export as semantic SVG attributes.

### 2.7 Export and Import

- Raw Export/Import:
  - Exclude elements where `customData.role` is `"grid"` or `"trace"`.
  - Serialize scene with Excalidraw JSON serialization functions.
  - Restore scene with Excalidraw restore functions.
- Semantic SVG Export:
  - Exclude guide elements.
  - Group elements inside `<g id="{sprite_name}">`.
  - Group sub-components inside `<g class="{layer_name}">`.
  - Convert `line` coordinates to SVG `<path>` elements (`d="M ... L ... Z"`).
  - Support `paint-order` to control stroke and fill order.

## 3. UI Controls and Shortcuts

- Thickness selector: buttons to select stroke thickness values (for example: 1, 3, 5, 10).
- Duplicate in place: Ctrl+D duplicates selected elements at their current positions with new IDs.
- Simplify selected lines: Ctrl+L reduces point count on selected `line` elements.
