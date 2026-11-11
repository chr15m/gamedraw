Run `clj-kondo` to lint after every change.

## Screenshots & Visual Testing

To capture screenshots of the running app (e.g. mobile viewport):
- Use Playwright with Node.js pointing to the system Chromium binary (`executablePath: '/usr/bin/chromium-browser'`), as the default Playwright cache (`~/.cache/ms-playwright`) is not pre-installed.
- Ensure the static server is running (`python3 -m http.server 8123 --directory src`), navigate to `http://localhost:8123/`, wait briefly for Scittle/Reagent to mount (`.app-root`), and call `page.screenshot()`.
- Use the `read` tool on the resulting PNG to inspect it visually.

