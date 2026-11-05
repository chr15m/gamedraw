// Phase 1 spike test protocol (SVG_REBUILD_SPEC.md section 5).
// Run: node tests/test_spike.js
const { chromium } = require('/root/pwtest/node_modules/playwright');
const assert = require('assert');

const URL = process.env.BASE_URL || 'http://localhost:8000/index.html';

async function shapeCount(page) {
  return page.evaluate(() => document.querySelectorAll('.canvas-svg g.world > path, .canvas-svg g.world > circle').length);
}
async function transform(page) {
  return page.evaluate(() => document.querySelector('.canvas-svg g').getAttribute('transform'));
}
async function drawStroke(page, pts, steps = 24) {
  await page.mouse.move(pts[0][0], pts[0][1]);
  await page.mouse.down();
  for (let i = 1; i < pts.length; i++) {
    for (let s = 1; s <= steps; s++) {
      const t = s / steps;
      const x = pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * t;
      const y = pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t;
      await page.mouse.move(x, y);
    }
  }
  await page.mouse.up();
  await page.waitForTimeout(150);
}

(async () => {
  const browser = await chromium.launch();
  const results = [];
  const pass = (name) => { results.push(`PASS ${name}`); console.log('PASS', name); };

  // --- Desktop context -----------------------------------------------------
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));
  await page.goto(URL, { waitUntil: 'load', timeout: 60000 });
  await page.waitForSelector('.canvas-svg', { timeout: 30000 });
  await page.waitForTimeout(1500);

  // 1. Drawing test
  await drawStroke(page, [[300, 380], [500, 300], [700, 430], [900, 350]]);
  let count = await shapeCount(page);
  let d = await page.evaluate(() => document.querySelector('.canvas-svg g.world > path')?.getAttribute('d') || '');
  assert(count >= 1, 'expected a committed shape');
  assert(d.length > 5, 'expected non-empty path d');
  pass('drawing adds an SVG path with non-empty d');
  await page.screenshot({ path: 'tests/baseline-screenshots/new-02-drawn.png' });

  // 4. Palette test (draw with a chosen color)
  await page.click('.swatch[title="#b45252"]');
  await drawStroke(page, [[350, 550], [650, 550], [950, 560]]);
  const strokes = await page.evaluate(() =>
    [...document.querySelectorAll('.canvas-svg g.world > path')].map((p) => p.getAttribute('stroke')));
  assert(strokes.includes('#b45252'), 'expected a stroke with selected palette color, got ' + JSON.stringify(strokes));
  pass('palette swatch sets stroke color');
  await page.screenshot({ path: 'tests/baseline-screenshots/new-03-palette.png' });

  // 2. Eraser test (erase across the red horizontal line at y=550)
  await page.click('.tool-btn:has-text("Erase")');
  await drawStroke(page, [[650, 480], [650, 620]]);
  const afterErase = await shapeCount(page);
  assert(afterErase < count + 2, 'eraser should remove intersected shape');
  const redGone = await page.evaluate(() =>
    ![...document.querySelectorAll('.canvas-svg g.world > path')].some((p) => p.getAttribute('stroke') === '#b45252'));
  assert(redGone, 'red stroke should have been erased');
  pass('eraser removes intersected shape');
  await page.screenshot({ path: 'tests/baseline-screenshots/new-04-erased.png' });

  // Re-add a shape for pan/zoom visual
  await page.click('.tool-btn:has-text("Draw")');
  await drawStroke(page, [[400, 250], [800, 250]]);

  // 3. Pan/Zoom test (synthetic wheel, ctrl = zoom)
  const t0 = await transform(page);
  await page.evaluate(() => {
    const svg = document.querySelector('.canvas-svg');
    const r = svg.getBoundingClientRect();
    svg.dispatchEvent(new WheelEvent('wheel', {
      bubbles: true, cancelable: true, clientX: r.width / 2, clientY: r.height / 2,
      deltaY: -240, ctrlKey: true,
    }));
  });
  await page.waitForTimeout(120);
  const t1 = await transform(page);
  assert(t0 !== t1, 'zoom should change the <g> transform');
  await page.evaluate(() => {
    const svg = document.querySelector('.canvas-svg');
    svg.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaX: 90, deltaY: 40 }));
  });
  await page.waitForTimeout(120);
  const t2 = await transform(page);
  assert(t1 !== t2, 'pan should change the <g> transform');
  pass('zoom + pan update the <g> transform');
  await page.screenshot({ path: 'tests/baseline-screenshots/new-05-panzoom.png' });

  // Undo / redo / simplify / duplicate / fill / closed smoke checks
  await page.click('.tool-btn:has-text("Select")');
  const t3before = await shapeCount(page);
  await page.keyboard.press('Control+z');
  await page.waitForTimeout(120);
  const t3after = await shapeCount(page);
  assert(t3after === t3before - 1, `undo should remove one shape (${t3before} -> ${t3after})`);
  await page.keyboard.press('Control+y');
  await page.waitForTimeout(120);
  assert((await shapeCount(page)) === t3before, 'redo should restore the shape');
  pass('undo/redo work');

  await page.close();

  // --- Mobile touch context ------------------------------------------------
  const mobile = await browser.newContext({
    viewport: { width: 390, height: 844 },
    hasTouch: true,
    isMobile: true,
    deviceScaleFactor: 3,
  });
  const mp = await mobile.newPage();
  mp.on('pageerror', (e) => console.log('[mobile pageerror]', e.message));
  await mp.goto(URL, { waitUntil: 'load', timeout: 60000 });
  await mp.waitForSelector('.canvas-svg', { timeout: 30000 });
  await mp.waitForTimeout(1500);
  const cdp = await mobile.newCDPSession(mp);

  // draw with one finger
  const touch = (type, points) => cdp.send('Input.dispatchTouchEvent',
    { type, touchPoints: points.map((p) => ({ x: p[0], y: p[1], id: p[2] || 0 })) });
  await touch('touchStart', [[120, 420, 1]]);
  for (let i = 1; i <= 24; i++) {
    await touch('touchMove', [[120 + i * 6, 420 + Math.sin(i / 4) * 40, 1]]);
  }
  await touch('touchEnd', []);
  await mp.waitForTimeout(200);
  const mobileShapes = await shapeCount(mp);
  assert(mobileShapes >= 1, 'touch drawing should commit a shape');
  pass('mobile touch drawing works');

  // 2-finger pinch pan/zoom
  const mpT0 = await transform(mp);
  await touch('touchStart', [[140, 400, 1], [240, 400, 2]]);
  for (let i = 1; i <= 10; i++) {
    await touch('touchMove', [[140 - i * 6, 400, 1], [240 + i * 6, 400, 2]]);
  }
  await touch('touchEnd', []);
  await mp.waitForTimeout(200);
  const mpT1 = await transform(mp);
  assert(mpT0 !== mpT1, 'two-finger gesture should change the transform');
  pass('mobile two-finger pan/zoom works');
  await mp.screenshot({ path: 'tests/baseline-screenshots/new-06-mobile.png' });

  await browser.close();
  console.log('\nALL SPIKE TESTS PASSED');
})();
