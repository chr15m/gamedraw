A drawing tool web app optimized for drawing vector game sprites by hand.

- Ability to change to named 'pages' where each page is one project.
- A hide-able guide underlay that shows the sprite grid starting at 0,0 with custom sprite size in pixels, but nothing outside that. This is so you can draw variations around the outside of the main area and then when you're ready you can put sprites into the actual grid positions they'll be read from. You can also customize the grid size in sprites e.g. 8x16 sprite slots (per page).
- Paste custom palette hex codes (one hex per line like lospec export format) to set the palette for the current page.
- Tool to draw single-thickness SVG style stroke paths that are easier to edit afterwards in an SVG editor like Inkscape.
- Ability to set stroke/fill order.
- Grouping of paths into a single sprite entity, and also within a sprite the ability to group logical objects like "head", "arm", etc. and have those appear on the expored SVG as classes and/or ID (e.g. ability to name the sprite in the id).
- Load images into the background, onion skinned, for tracing to get the hand drawn feel from reference material.
- Export/import of the raw data and also SVG like the default excalidraw tool.
