# Current dev

## TODO

- [ ] Threshold pinch to zoom so two finger dragging doesn't trigger it so easily.

## Next

- [ ] sync to nostr
- [ ] Display size of current page definition stringified in kb in top right
- [ ] Implement stroke and fill order toggle
- [ ] Support custom palette import from hex lists (Lospec format)
- [ ] Organize pages into projects (project-level grouping or snapshots)

## Done

- [x] Move delete/duplicate buttons under a vertical dots icon that pops up.
- [x] Put undo and redo into the bottom toolbar just to the right of the magnifying glass.
- [x] Put delete/duplicate actions from top bar to bottom, to the left of undo/redo.

- [x] Hide the 'hand' to move on mobile.
- [x] Put the undo/redo/delete/duplicate icons on one line together below the page name.
- [x] Previously selected pin board is not being recalled.
- [x] Each selected shape should get its own dotted-line box around it.
- [x] When drag-rectangle selecting shapes inside other shapes it should only select shapes that are completely within the rectangle.
- [x] Move pallette toggle button to bottom right, left-align the other tools there.
- [x] Order the tools: Draw, erase, select.
- [x] Remove the word 'Canvas' from the pin board close button.

- [x] Put the 'pin UI' toggle to the right of the palette toggle.
- [x] Implement a drag-rectangle-to-select UI for the pointer.
- [x] Collapse the zoom UI into a single magnifying glass icon that pops out.
- [x] When selecting a shape, do not select if a transparent portion is tapped/clicked.
- [x] Fill and close should be tied together to prevent impossible states like fill with no close. E.g. Un-closing a filled shape should not show 'fill' as selected, or show it as greyed out selected.
- [x] Don't show the "edit name" field under hamburger.
- [x] Put an edit button next to each page name which turns it into an editable input.
- [x] Remember last selected board in pin ui.
- [x] Drag to move selected shape around.
- [x] Bug: after selecting a shape, tapping any resize corner instantly collapses the rectangle to zero w/h at the point of the corner where you tapped.
- [x] Now that the two export buttons are in the hamburger menu, remove them from the UI buttons.
- [x] Bug: a single dot turns into a small circle after converting
- [x] Change UI words into tabler.io icons
- [x] Put the palette interface behind an icon toggle
- [x] Put a hamburger menu in the top left
- [x] Instead of a literal date, the page name should be a string
- [x] List of all pages in the hamburger menu under 'pages'
- [x] Browse and import reference images from a Pinterest board
- [x] Ability to select palettes with CC-29 lospec as an option
- [x] Pages should be dates and default to 'today' when loaded
- [x] Persist pages to IndexedDb (idb-keyval)
- [x] Implement line simplification for tldraw draw shapes (decode and encode b64Vecs with simplify-js)
- [x] Ability to delete points easily
- [x] Hide unnecessary default tools from toolbar (arrows, geo shapes, notes, frames, laser)
- [x] Implement constant-width strokes (disable pressure variance for sprite drawing)
- [x] Bug: sometimes when "simplifying" it results in even more points

## Maybe Later

- [ ] Implement sprite underlay grid guide starting at (0, 0) with custom dimensions and slot counts

