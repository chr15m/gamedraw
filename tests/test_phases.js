// Phase 2-5 verification tests.
// Run: node tests/test_phases.js  (or: npm test)
let playwright;
try { playwright = require('playwright'); } catch (e) { playwright = require('/root/pwtest/node_modules/playwright'); }
const { chromium } = playwright;
const assert = require('assert');
const fs = require('fs');
const http = require('http');
const path = require('path');

const mimeTypes = {
  '.html': 'text/html',
  '.css': 'text/css',
  '.js': 'text/javascript',
  '.cljs': 'application/x-scittle',
  '.png': 'image/png',
  '.svg': 'image/svg+xml'
};

// Serve src/ over http so IndexedDB / module loading behave like production.
function startStaticServer() {
  const srcDir = path.resolve(__dirname, '..', 'src');
  const server = http.createServer((req, res) => {
    const reqPath = req.url.split('?')[0];
    const file = path.join(srcDir, reqPath === '/' ? 'index.html' : reqPath);
    if (fs.existsSync(file) && fs.statSync(file).isFile()) {
      res.writeHead(200, { 'Content-Type': mimeTypes[path.extname(file)] || 'text/plain' });
      fs.createReadStream(file).pipe(res);
    } else {
      res.writeHead(404);
      res.end('Not Found');
    }
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      resolve({ server, url: `http://127.0.0.1:${server.address().port}/index.html` });
    });
  });
}

const bboxOf = (page) => page.evaluate(() => {
  const p = document.querySelector('.canvas-svg g.world > path');
  const b = p.getBBox();
  return { x: b.x, y: b.y, w: b.width, h: b.height };
});
const qCount = (page) => page.evaluate(() => {
  const p = document.querySelector('.canvas-svg g.world > path');
  return ((p.getAttribute('d').match(/ Q /g) || []).length);
});
const shapeCount = (page) => page.evaluate(() =>
  document.querySelectorAll('.canvas-svg g.world > path, .canvas-svg g.world > circle').length);

async function makeFresh(page, url) {
  await page.goto(url, { waitUntil: 'load', timeout: 60000 });
  await page.waitForSelector('.canvas-svg', { timeout: 30000 });
  await page.evaluate(() => window.idbKeyval && window.idbKeyval.clear());
  await page.reload({ waitUntil: 'load' });
  await page.waitForSelector('.canvas-svg', { timeout: 30000 });
  await page.waitForTimeout(900);
}

async function drawStroke(page, pts, steps = 30) {
  await page.mouse.move(pts[0][0], pts[0][1]);
  await page.mouse.down();
  for (let i = 1; i < pts.length; i++)
    for (let s = 1; s <= steps; s++) {
      const t = s / steps;
      await page.mouse.move(pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * t,
                            pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t);
    }
  await page.mouse.up();
  await page.waitForTimeout(120);
}

const selectTool = (page) => page.click('.tool-btn:has-text("Select")');
const drawTool = (page) => page.click('.tool-btn:has-text("Draw")');

async function selectShape(page, x, y) {
  await selectTool(page);
  await page.mouse.click(x, y);
  await page.waitForTimeout(150);
}

// Poll until the active toolbar tool matches, avoiding React render races.
const expectTool = (page, title) =>
  page.waitForFunction((t) => document.querySelector('.tool-btn.active')?.title === t, title, { timeout: 5000 });

(async () => {
  let staticServer;
  let testUrl = process.env.BASE_URL;

  if (!testUrl) {
    const s = await startStaticServer();
    staticServer = s.server;
    testUrl = s.url;
  }

  const fresh = (p) => makeFresh(p, testUrl);

  const executablePath = process.env.CHROMIUM_PATH ||
    ['/usr/bin/chromium-browser', '/usr/bin/chromium', '/usr/bin/google-chrome']
      .find((p) => fs.existsSync(p));

  const browser = await chromium.launch({ executablePath, args: ['--no-sandbox'] });

  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    page.on('console', (m) => { if (m.type() === 'error') console.log('[browser error]', m.text()); });
    page.on('pageerror', (e) => console.log('[pageerror]', e.message));
    const pass = (n) => console.log('PASS', n);

    // ---------------- Phase 2: selection, move, duplicate, scale, rotate ----
    await fresh(page);
    await drawStroke(page, [[300, 400], [500, 400], [500, 500]]);
    await selectShape(page, 400, 400);
    assert(await page.$('rect[stroke="#4465e9"]'), 'selection overlay should show');
    pass('click selects a shape (bounding box overlay)');

    const b0 = await bboxOf(page);
    await page.mouse.move(400, 400);
    await page.mouse.down();
    await page.mouse.move(500, 450, { steps: 8 });
    await page.mouse.up();
    await page.waitForTimeout(120);
    const b1 = await bboxOf(page);
    assert(Math.abs(b1.x - b0.x) > 50 && Math.abs(b1.y - b0.y) > 20, 'move should translate shape');
    pass('drag moves selection');

    await page.keyboard.press('Control+d');
    await page.waitForTimeout(150);
    assert((await shapeCount(page)) === 2, 'duplicate should add one shape');
    const dupSame = await page.evaluate(() => {
      const ps = [...document.querySelectorAll('.canvas-svg g.world > path')];
      return ps[0].getAttribute('d') === ps[1].getAttribute('d');
    });
    assert(dupSame, 'duplicate-in-place should share coordinates');
    pass('Ctrl-D duplicates in place');

    await page.keyboard.press('Control+z');
    await page.waitForTimeout(120);
    assert((await shapeCount(page)) === 1, 'undo duplicate');
    pass('undo/redo history');

    // scale via corner handle
    await selectShape(page, 500, 450);
    const sb = await bboxOf(page);
    const handle = (await page.$$('rect[fill="#ffffff"][stroke="#4465e9"]'))[1];
    const hb = await handle.boundingBox();
    await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
    await page.mouse.down();
    await page.mouse.move(hb.x + 140, hb.y + 140, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(150);
    const sb2 = await bboxOf(page);
    assert(sb2.w > sb.w + 20 || sb2.h > sb.h + 20, 'scale handle should enlarge shape');
    pass('corner handle scales selection');

    // rotate handle
    const rb = await (await page.$('circle[stroke="#4465e9"]')).boundingBox();
    await page.mouse.move(rb.x + rb.width / 2, rb.y + rb.height / 2);
    await page.mouse.down();
    await page.mouse.move(rb.x + 120, rb.y + 60, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(150);
    pass('rotate handle rotates selection');

    // marquee select + delete
    await drawTool(page);
    await drawStroke(page, [[800, 300], [900, 350]]);
    await selectTool(page);
    await page.mouse.move(200, 200);
    await page.mouse.down();
    await page.mouse.move(1000, 750, { steps: 12 });
    await page.mouse.up();
    await page.waitForTimeout(150);
    await page.keyboard.press('Delete');
    await page.waitForTimeout(150);
    assert((await shapeCount(page)) === 0, 'marquee + delete should clear all shapes');
    pass('marquee selection + delete');
    await page.screenshot({ path: 'tests/baseline-screenshots/new-07-select.png' });

    // ---------------- Toolbar: zoom popout -----------------------------------
    await page.click('.zoom-toggle-btn');
    await page.waitForSelector('.zoom-popout');
    await page.click('.zoom-popout button[title="Zoom in"]');
    await page.waitForTimeout(120);
    const zoomPctIn = await page.evaluate(() => document.querySelector('.zoom-label-btn').innerText.trim());
    assert(zoomPctIn === '125%', `zoom in button should increase zoom to 125%, got ${zoomPctIn}`);

    await page.click('.zoom-popout button[title="Zoom out"]');
    await page.waitForTimeout(120);
    const zoomPctOut = await page.evaluate(() => document.querySelector('.zoom-label-btn').innerText.trim());
    assert(zoomPctOut === '100%', `zoom out button should return zoom to 100%, got ${zoomPctOut}`);

    await page.click('.zoom-backdrop');
    await page.waitForTimeout(100);
    assert(!(await page.$('.zoom-popout')), 'zoom backdrop click should dismiss popout');
    pass('Toolbar zoom popout (zoom in, zoom out, reset, backdrop dismiss)');

    // ---------------- Contextual toolbar: selection actions ------------------
    await drawTool(page);
    await drawStroke(page, [[400, 300], [600, 300]]);
    await selectShape(page, 500, 300);
    const countBeforeActions = await shapeCount(page);

    await page.waitForSelector('.contextual-toolbar');
    await page.click('.contextual-toolbar button[title="Duplicate (Ctrl-D)"]');
    await page.waitForTimeout(150);
    assert((await shapeCount(page)) === countBeforeActions + 1, 'Contextual duplicate should duplicate shape');

    await page.click('.contextual-toolbar button[title="Delete (Del)"]');
    await page.waitForTimeout(150);
    assert((await shapeCount(page)) === countBeforeActions, 'Contextual delete should delete selection');
    pass('Toolbar contextual selection actions (duplicate button, delete button)');

    // ---------------- Toolbar: keyboard tool shortcuts -----------------------
    await page.keyboard.press('v');
    await expectTool(page, 'Select');
    await page.keyboard.press('d');
    await expectTool(page, 'Draw');
    await page.keyboard.press('e');
    await expectTool(page, 'Eraser');
    await page.keyboard.press('b');
    await expectTool(page, 'Flood fill');
    await page.keyboard.press('f');
    await expectTool(page, 'Flood fill');
    await page.keyboard.press('h');
    await expectTool(page, 'Pan');

    await selectShape(page, 500, 300);
    assert(await page.$('g.overlay'), 'selection should be active');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);
    assert(!(await page.$('g.overlay')), 'Escape should clear selection');
    pass('Keyboard tool switching shortcuts (v, d, e, h, Escape)');

    // ---------------- Phase 3: simplify, closed, fill, sizes, palettes, BG ---
    await fresh(page);
    await drawTool(page);
    const pts = [];
    for (let i = 0; i <= 40; i++) pts.push([250 + i * 16, 380 + Math.sin(i / 2) * 60]);
    await drawStroke(page, pts, 4);
    await selectShape(page, 250 + 20 * 16, 380 + Math.sin(20 / 2) * 60);
    const qBefore = await qCount(page);
    if (!(await page.$('.style-panel'))) {
      await page.click('.palette-toggle-btn');
      await page.waitForSelector('.style-panel');
    }
    await page.click('.mini-btn:has-text("simplify")');
    await page.waitForTimeout(200);
    const qAfter = await qCount(page);
    assert(qAfter < qBefore, `simplify should reduce points (${qBefore} -> ${qAfter})`);
    pass('Phase 3 simplify reduces vertices');

    await page.click('.mini-btn:has-text("close")');
    await page.waitForTimeout(120);
    const closedD = await page.evaluate(() => document.querySelector('.canvas-svg g.world > path').getAttribute('d'));
    assert(/\sZ$/.test(closedD.trim()), 'closed toggle should append Z');
    pass('Phase 3 open/closed toggle appends Z');

    await page.click('.mini-btn:has-text("fill")');
    await page.waitForTimeout(120);
    const fill = await page.evaluate(() => document.querySelector('.canvas-svg g.world > path').getAttribute('fill'));
    assert(fill && fill !== 'none', 'fill toggle should set a solid fill');
    pass('Phase 3 fill toggle sets solid fill');

    await page.click('.sizes .mini-btn:text-is("L")');
    await page.waitForTimeout(120);
    const sw = await page.evaluate(() => document.querySelector('.canvas-svg g.world > path').getAttribute('stroke-width'));
    assert(parseFloat(sw) === 5, 'L size should be 5px, got ' + sw);
    pass('Phase 3 stroke size presets');

    // Palettes dropdown & Background color mode
    const cc29Swatches = await page.$$eval('.swatches .swatch', (els) => els.length);
    assert(cc29Swatches === 29, `CC-29 should have 29 swatches, got ${cc29Swatches}`);

    await page.selectOption('.palette-select', 'tldraw');
    await page.waitForTimeout(150);
    const tldrawSwatches = await page.$$eval('.swatches .swatch', (els) => els.length);
    assert(tldrawSwatches === 12, `Default (tldraw) should have 12 swatches, got ${tldrawSwatches}`);
    pass('Palette selector switches palette presets (CC-29 -> tldraw)');

    // Artboard background mode
    await page.click('.bg-btn');
    await page.waitForTimeout(100);
    assert(await page.$('.bg-btn.active'), 'bg-btn should be active');
    await page.click('.swatch[title="#4465e9"]');
    await page.waitForTimeout(150);
    const bgFill = await page.evaluate(() => document.querySelector('.canvas-svg > rect').getAttribute('fill'));
    assert(bgFill === '#4465e9', `canvas background should update to #4465e9, got ${bgFill}`);
    await page.click('.bg-btn');
    await page.waitForTimeout(100);
    assert(!(await page.$('.bg-btn.active')), 'bg-btn should toggle off');
    pass('Artboard background mode (.bg-btn toggle + swatch selection)');
    await page.screenshot({ path: 'tests/baseline-screenshots/new-08-path-tools.png' });

    // ---------------- Vector Flood Fill --------------------------------------
    await fresh(page);
    const defaultBg = await page.evaluate(() => document.querySelector('.canvas-svg rect')?.getAttribute('fill'));
    if (!(await page.$('.style-panel'))) {
      await page.click('.palette-toggle-btn');
      await page.waitForSelector('.style-panel');
    }
    await page.click('.swatch[title="#4b80ca"]');
    await page.keyboard.press('b');
    await expectTool(page, 'Flood fill');

    // Click on empty canvas -> changes background color
    await page.mouse.click(200, 200);
    await page.waitForTimeout(150);
    assert((await page.evaluate(() => document.querySelector('.canvas-svg rect')?.getAttribute('fill'))) === '#4b80ca',
           'empty canvas click should set bg color');
    await page.keyboard.press('Control+z');
    await page.waitForTimeout(150);
    assert((await page.evaluate(() => document.querySelector('.canvas-svg rect')?.getAttribute('fill'))) === defaultBg,
           'undo should restore background color');

    // Draw closed shape and fill interior
    await drawTool(page);
    const boxPts = [[300, 300], [500, 300], [500, 500], [300, 500], [300, 300]];
    await drawStroke(page, boxPts, 10);
    assert((await shapeCount(page)) === 1, 'stroke drawn');

    await page.keyboard.press('b');
    await page.mouse.click(400, 400);
    await page.waitForTimeout(250);
    assert((await shapeCount(page)) === 2, 'flood fill inside closed shape should add a fill shape');

    const fillInfo = await page.evaluate(() => {
      const ps = [...document.querySelectorAll('.canvas-svg g.world > path')];
      return {
        firstStroke: ps[0].getAttribute('stroke'),
        firstFill: ps[0].getAttribute('fill'),
        bg: document.querySelector('.canvas-svg rect')?.getAttribute('fill')
      };
    });
    assert(fillInfo.firstStroke === 'none', 'fill path should have stroke none');
    assert(fillInfo.firstFill === '#4b80ca', 'fill path should have selected color');
    assert(fillInfo.bg === defaultBg, 'canvas background should not change for interior fill');

    // Click outside bounding box of shapes -> sets bg color
    await page.mouse.click(100, 100);
    await page.waitForTimeout(150);
    assert((await page.evaluate(() => document.querySelector('.canvas-svg rect')?.getAttribute('fill'))) === '#4b80ca',
           'click outside bbox should set bg color');
    await page.keyboard.press('Control+z');
    await page.waitForTimeout(150);
    assert((await page.evaluate(() => document.querySelector('.canvas-svg rect')?.getAttribute('fill'))) === defaultBg,
           'undo should restore background color after outside click');

    // Open shape -> leak to bbox -> sets bg color without adding shape
    await page.click('.menu-btn');
    await page.waitForSelector('.hamburger-menu');
    await page.click('.hamburger-menu button:has-text("Clear canvas")');
    await page.waitForTimeout(200);

    await drawTool(page);
    const uShape = [[300, 300], [300, 500], [500, 500], [500, 300]];
    await drawStroke(page, uShape, 10);
    assert((await shapeCount(page)) === 1);

    await page.keyboard.press('b');
    await page.mouse.click(400, 450);
    await page.waitForTimeout(250);
    assert((await shapeCount(page)) === 1, 'uncontained fill should not create a shape');
    assert((await page.evaluate(() => document.querySelector('.canvas-svg rect')?.getAttribute('fill'))) === '#4b80ca',
           'uncontained fill leaking to bbox should set bg color');
    pass('Vector flood fill (interior fill, bbox leak to background, click outside to background, undo)');

    // ---------------- Phase 4: vertex editing & insertion ---------------------
    await fresh(page);
    await drawTool(page);
    await drawStroke(page, [[300, 350], [500, 450], [700, 300]]);
    // Simplify so the shape has a handful of well-separated vertices, giving us
    // a clean segment to insert a new vertex into.
    await selectShape(page, 400, 400);
    await page.keyboard.press('Control+l');
    await page.waitForTimeout(200);
    await page.mouse.dblclick(500, 450);
    await page.waitForTimeout(200);
    const handlesBefore = await page.evaluate(() => document.querySelectorAll('circle[stroke="#e03131"]').length);
    assert(handlesBefore >= 2, 'double-click should enter vertex mode with handles, got ' + handlesBefore);
    pass('Phase 4 double-click enters vertex edit mode');

    // vertex insertion: click the midpoint of the first segment ([300,350] .. [500,450])
    const vCountBeforeInsert = await page.evaluate(() => document.querySelectorAll('circle[stroke="#e03131"]').length);
    await page.mouse.click(400, 400);
    await page.waitForTimeout(200);
    const vCountAfterInsert = await page.evaluate(() => document.querySelectorAll('circle[stroke="#e03131"]').length);
    assert(vCountAfterInsert === vCountBeforeInsert + 1,
      `clicking stroke should insert vertex handle (${vCountBeforeInsert} -> ${vCountAfterInsert})`);
    pass('Phase 4 vertex insertion by clicking on stroke in vertex edit mode');

    // drag a vertex handle
    const vh = await (await page.$$('circle[stroke="#e03131"]'))[1];
    const vb = await vh.boundingBox();
    const dBefore = await page.evaluate(() => document.querySelector('.canvas-svg g.world > path').getAttribute('d'));
    await page.mouse.move(vb.x + vb.width / 2, vb.y + vb.height / 2);
    await page.mouse.down();
    await page.mouse.move(vb.x + 120, vb.y - 90, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(150);
    const dAfter = await page.evaluate(() => document.querySelector('.canvas-svg g.world > path').getAttribute('d'));
    assert(dBefore !== dAfter, 'dragging a vertex should change path geometry');
    pass('Phase 4 drag vertex updates geometry');

    // double-click a handle deletes it
    const vh2 = await (await page.$$('circle[stroke="#e03131"]'))[1];
    const vb2 = await vh2.boundingBox();
    const hCountBefore = await page.evaluate(() => document.querySelectorAll('circle[stroke="#e03131"]').length);
    await page.mouse.dblclick(vb2.x + vb2.width / 2, vb2.y + vb2.height / 2);
    await page.waitForTimeout(200);
    const hCountAfter = await page.evaluate(() => document.querySelectorAll('circle[stroke="#e03131"]').length);
    assert(hCountAfter === hCountBefore - 1, `vertex delete should remove one (${hCountBefore} -> ${hCountAfter})`);
    pass('Phase 4 double-click vertex deletes it');
    await page.screenshot({ path: 'tests/baseline-screenshots/new-09-vertices.png' });

    // ---------------- Phase 5: export + persistence + trace ------------------
    await fresh(page);
    await drawTool(page);
    await drawStroke(page, [[300, 300], [600, 500]]);
    await page.click('.menu-btn');
    await page.waitForSelector('.hamburger-menu');
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('.hamburger-menu button:has-text("Export SVG")'),
    ]);
    const file = await download.path();
    const content = fs.readFileSync(file, 'utf8');
    assert(content.startsWith('<svg') && content.includes('<path'), 'export should be standalone SVG');
    pass('Phase 5 SVG export downloads valid SVG');

    // persistence across reload. Saves are debounced, so give the timer time
    // to fire before reloading.
    await page.waitForTimeout(1000);
    const savedShapeCount = await page.evaluate(async () => {
      const keys = await window.idbKeyval.keys();
      const data = keys.length ? await window.idbKeyval.get(keys[0]) : null;
      return data && data.shapes ? data.shapes.length : 0;
    });
    assert(savedShapeCount >= 1, 'shape should be saved to IndexedDB before reload');

    await page.reload({ waitUntil: 'load' });
    await page.waitForSelector('.canvas-svg', { timeout: 30000 });
    await page.waitForFunction(() =>
      document.querySelectorAll('.canvas-svg g.world > path, .canvas-svg g.world > circle').length >= 1,
      null, { timeout: 10000 });
    pass('Phase 5 IndexedDB page persistence');

    // trace layer via file input
    if (!(await page.$('.style-panel'))) {
      await page.click('.palette-toggle-btn');
      await page.waitForSelector('.style-panel');
    }
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC',
      'base64');
    await page.setInputFiles('input[type="file"]', { name: 'trace.png', mimeType: 'image/png', buffer: png });
    await page.waitForTimeout(600);
    const hasImage = await page.evaluate(() => !!document.querySelector('.canvas-svg image'));
    assert(hasImage, 'trace image should be added to canvas');
    pass('Phase 5 trace image layer');
    await page.screenshot({ path: 'tests/baseline-screenshots/new-10-trace-export.png' });

    // ---------------- Page management UI ------------------------------------
    // Close the style panel so it cannot cover the menu.
    if (await page.$('.style-panel')) {
      await page.click('.palette-toggle-btn');
      await page.waitForTimeout(200);
    }

    const pageWithShapes = await page.evaluate(() => document.querySelector('.top-bar .page-title').innerText.trim());

    // Create a new page
    await page.click('.menu-btn');
    await page.waitForSelector('.hamburger-menu');
    await page.click('.menu-section-action[title="New page"]');
    await page.waitForFunction((prev) => document.querySelector('.top-bar .page-title')?.innerText.trim() !== prev,
      pageWithShapes, { timeout: 5000 });
    const newPageTitle = await page.evaluate(() => document.querySelector('.top-bar .page-title').innerText.trim());
    assert(newPageTitle !== pageWithShapes, 'top bar title should update on new page');
    assert((await shapeCount(page)) === 0, 'new page should be empty');

    // Rename the new page inline
    await page.click('.menu-btn');
    await page.waitForSelector('.hamburger-menu');
    await page.locator('.page-item-row').filter({ hasText: newPageTitle }).locator('.page-edit-btn').click();
    await page.waitForSelector('.page-item-row.editing .page-item-input');
    await page.locator('.page-item-row.editing .page-item-input').fill('custom-drawing-1');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() =>
      document.querySelector('.top-bar .page-title')?.innerText.trim() === 'custom-drawing-1', null, { timeout: 5000 });
    pass('Phase 5 new page + inline rename');

    // Switch back to the page that has shapes
    if (!(await page.$('.hamburger-menu'))) {
      await page.click('.menu-btn');
      await page.waitForSelector('.hamburger-menu');
    }
    await page.locator('.page-item-row').filter({ hasText: pageWithShapes }).locator('.page-item-select').click();
    await page.waitForFunction((init) => document.querySelector('.top-bar .page-title')?.innerText.trim() === init,
      pageWithShapes, { timeout: 5000 });
    await page.waitForFunction(() =>
      document.querySelectorAll('.canvas-svg g.world > path, .canvas-svg g.world > circle').length >= 1,
      null, { timeout: 10000 });
    assert((await shapeCount(page)) >= 1, 'switched page should have original shapes restored');
    pass('Phase 5 page switching restores shapes');

    console.log('\nALL E2E TESTS PASSED');
  } catch (err) {
    console.error('TEST ERROR:', err.stack);
    throw err;
  } finally {
    await browser.close();
    if (staticServer) staticServer.close();
  }
})();
