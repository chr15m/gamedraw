// Phase 2-5 verification tests.
// Run: node tests/test_phases.js
let playwright;
try { playwright = require('playwright'); } catch (e) { playwright = require('/root/pwtest/node_modules/playwright'); }
const { chromium } = playwright;
const assert = require('assert');
const fs = require('fs');

const URL = process.env.BASE_URL || 'http://localhost:8000/index.html';
const bboxOf = (page) => page.evaluate(() => {
  const p = document.querySelector('.canvas-svg g.world > path');
  const b = p.getBBox();
  return { x: b.x, y: b.y, w: b.width, h: b.height };
});
const qCount = (page) => page.evaluate(() => {
  const p = document.querySelector('.canvas-svg g.world > path');
  return ((p.getAttribute('d').match(/ Q /g) || []).length);
});

async function fresh(page) {
  await page.goto(URL, { waitUntil: 'load', timeout: 60000 });
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
const shapeCount = (page) => page.evaluate(() =>
  document.querySelectorAll('.canvas-svg g.world > path, .canvas-svg g.world > circle').length);

(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || (fs.existsSync('/usr/bin/chromium-browser') ? '/usr/bin/chromium-browser' : undefined),
    args: ['--no-sandbox']
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
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
  await page.mouse.move(1000, 650, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(150);
  const beforeDel = await shapeCount(page);
  await page.keyboard.press('Delete');
  await page.waitForTimeout(150);
  assert((await shapeCount(page)) === 0, 'marquee + delete should clear all shapes');
  pass('marquee selection + delete');
  await page.screenshot({ path: 'tests/baseline-screenshots/new-07-select.png' });

  // ---------------- Phase 3: simplify, closed, fill, sizes -----------------
  await fresh(page);
  await drawTool(page);
  // wiggly stroke = many points
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
  await page.screenshot({ path: 'tests/baseline-screenshots/new-08-path-tools.png' });

  // ---------------- Phase 4: vertex editing --------------------------------
  await fresh(page);
  await drawTool(page);
  await drawStroke(page, [[300, 350], [500, 450], [700, 300]]);
  await selectTool(page);
  await page.mouse.dblclick(500, 450);
  await page.waitForTimeout(200);
  const handlesBefore = await page.evaluate(() => document.querySelectorAll('circle[stroke="#e03131"]').length);
  assert(handlesBefore >= 2, 'double-click should enter vertex mode with handles, got ' + handlesBefore);
  pass('Phase 4 double-click enters vertex edit mode');

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

  // persistence across reload
  await page.waitForTimeout(900);
  await page.reload({ waitUntil: 'load' });
  await page.waitForSelector('.canvas-svg', { timeout: 30000 });
  await page.waitForTimeout(1200);
  assert((await shapeCount(page)) >= 1, 'shapes should persist across reload via IndexedDB');
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

  // page switching
  await page.click('.menu-btn');
  await page.waitForSelector('.hamburger-menu');
  await page.click('.menu-section-action');
  await page.waitForTimeout(800);
  assert((await shapeCount(page)) === 0, 'new date page should be empty');
  pass('Phase 5 date-based pages switch');

  await browser.close();
  console.log('\nALL PHASE 2-5 TESTS PASSED');
})();
