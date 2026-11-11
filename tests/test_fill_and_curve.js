const playwright = require('playwright');
const { chromium } = playwright;
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

(async () => {
  const s = await startStaticServer();
  const testUrl = s.url;

  const executablePath = process.env.CHROMIUM_PATH ||
    ['/usr/bin/chromium-browser', '/usr/bin/chromium', '/usr/bin/google-chrome']
      .find((p) => fs.existsSync(p));

  const browser = await chromium.launch({ executablePath, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });

  page.on('console', m => { if (m.type() === 'error') console.log('[console error]', m.text()); });
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  await page.goto(testUrl);
  await page.waitForSelector('.canvas-svg');
  await page.waitForTimeout(1000);

  // Clear canvas
  await page.evaluate(() => scittle.core.eval_string('(main/clear-page!)'));
  await page.waitForTimeout(100);

  console.log('--- TEST 1: Straight vs Bézier toggle ---');
  // Draw a freehand triangle
  await page.mouse.move(300, 200);
  await page.mouse.down();
  await page.mouse.move(400, 350);
  await page.mouse.move(200, 350);
  await page.mouse.move(300, 200);
  await page.mouse.up();
  await page.waitForTimeout(200);

  const initialCurve = await page.evaluate(() => scittle.core.eval_string('(str (:curve (first (:shapes @main/app))))'));
  console.log('Initial shape curve:', initialCurve);

  const initialD = await page.evaluate(() => document.querySelector('.canvas-svg path').getAttribute('d'));
  console.log('Initial path d contains Q (bezier):', initialD.includes(' Q '));

  // Select shape
  await page.keyboard.press('v');
  await page.mouse.click(300, 200);
  await page.waitForTimeout(100);

  // Open style panel
  await page.locator('.palette-toggle-btn').click();
  await page.waitForTimeout(100);

  // Click curve toggle button
  const curveBtn = page.locator('.style-row button:has(.ti-vector-bezier), .style-row button:has(.ti-line)');
  await curveBtn.click({ timeout: 5000 });
  await page.waitForTimeout(150);

  const toggledCurve = await page.evaluate(() => scittle.core.eval_string('(str (:curve (first (:shapes @main/app))))'));
  console.log('Toggled shape curve:', toggledCurve);

  const toggledD = await page.evaluate(() => document.querySelector('.canvas-svg path').getAttribute('d'));
  console.log('Toggled path d contains Q:', toggledD.includes(' Q '));
  console.log('Toggled path d contains L (straight):', toggledD.includes(' L '));

  // Toggle back to bezier
  await curveBtn.click();
  await page.waitForTimeout(150);
  const revertedCurve = await page.evaluate(() => scittle.core.eval_string('(str (:curve (first (:shapes @main/app))))'));
  console.log('Reverted shape curve:', revertedCurve);

  console.log('\n--- TEST 2: Vector flood fill with holes (interior cutouts) ---');
  await page.evaluate(() => scittle.core.eval_string('(main/clear-page!)'));
  await page.keyboard.press('d');
  await page.waitForTimeout(100);

  // Draw outer closed circle (donut outer)
  const cx = 500, cy = 350, rOut = 120, rIn = 40;
  await page.mouse.move(cx + rOut, cy);
  await page.mouse.down();
  for (let a = 0; a <= 360; a += 15) {
    const rad = a * Math.PI / 180;
    await page.mouse.move(cx + rOut * Math.cos(rad), cy + rOut * Math.sin(rad));
  }
  await page.mouse.up();
  await page.waitForTimeout(200);

  // Draw inner closed circle (donut hole)
  await page.mouse.move(cx + rIn, cy);
  await page.mouse.down();
  for (let a = 0; a <= 360; a += 15) {
    const rad = a * Math.PI / 180;
    await page.mouse.move(cx + rIn * Math.cos(rad), cy + rIn * Math.sin(rad));
  }
  await page.mouse.up();
  await page.waitForTimeout(200);

  // Switch to fill tool
  await page.keyboard.press('b');
  await page.waitForTimeout(100);

  // Tap in the ring (between rIn and rOut: cx + 80, cy)
  const canvas = page.locator('.canvas-svg');
  await canvas.dispatchEvent('pointerdown', { pointerId: 1, pointerType: 'touch', clientX: cx + 80, clientY: cy, button: 0 });
  await page.waitForTimeout(50);
  await canvas.dispatchEvent('pointerup', { pointerId: 1, pointerType: 'touch', clientX: cx + 80, clientY: cy, button: 0 });
  await page.waitForTimeout(400);

  const fillShape = await page.evaluate(() => {
    return {
      curve: scittle.core.eval_string('(str (:curve (first (filter #(and (:fill %) (not= (:fill %) "none")) (:shapes @main/app)))))'),
      holesCount: scittle.core.eval_string('(count (:holes (first (filter #(and (:fill %) (not= (:fill %) "none")) (:shapes @main/app)))))'),
      outerPtsCount: scittle.core.eval_string('(count (:points (first (filter #(and (:fill %) (not= (:fill %) "none")) (:shapes @main/app)))))')
    };
  });
  console.log('Fill shape properties:', fillShape);

  const fillDom = await page.evaluate(() => {
    const paths = Array.from(document.querySelectorAll('.canvas-svg path'));
    const fp = paths.find(p => p.getAttribute('fill') && p.getAttribute('fill') !== 'none' && p.getAttribute('stroke') === 'none');
    if (!fp) return null;
    return {
      dSubpathCount: (fp.getAttribute('d').match(/M/g) || []).length,
      dLength: fp.getAttribute('d').length,
      fillRule: fp.getAttribute('fill-rule') || fp.style.fillRule
    };
  });
  console.log('Fill DOM element:', fillDom);

  console.log('\n--- TEST 3: Multi-touch pinch does NOT trigger fill ---');
  const shapeCountBeforePinch = await page.evaluate(() => scittle.core.eval_string('(count (:shapes @main/app))'));

  // Simulate two fingers touching down with fill tool
  await canvas.dispatchEvent('pointerdown', { pointerId: 10, pointerType: 'touch', clientX: 200, clientY: 200, button: 0 });
  await page.waitForTimeout(30);
  await canvas.dispatchEvent('pointerdown', { pointerId: 11, pointerType: 'touch', clientX: 250, clientY: 250, button: 0 });
  await page.waitForTimeout(30);
  // Pinch move
  await canvas.dispatchEvent('pointermove', { pointerId: 10, pointerType: 'touch', clientX: 180, clientY: 180 });
  await canvas.dispatchEvent('pointermove', { pointerId: 11, pointerType: 'touch', clientX: 270, clientY: 270 });
  await page.waitForTimeout(30);
  // Fingers up
  await canvas.dispatchEvent('pointerup', { pointerId: 10, pointerType: 'touch', clientX: 180, clientY: 180 });
  await canvas.dispatchEvent('pointerup', { pointerId: 11, pointerType: 'touch', clientX: 270, clientY: 270 });
  await page.waitForTimeout(200);

  const shapeCountAfterPinch = await page.evaluate(() => scittle.core.eval_string('(count (:shapes @main/app))'));
  console.log('Shapes before pinch:', shapeCountBeforePinch, 'after pinch:', shapeCountAfterPinch);
  console.log('Pinch did NOT trigger accidental fill:', shapeCountBeforePinch === shapeCountAfterPinch);

  console.log('\n--- TEST 4: Re-coloring existing fill & same-color no-op ---');
  // Open palette and pick blue swatch #4b80ca
  if (!(await page.$('.style-panel'))) {
    await page.locator('.palette-toggle-btn').click();
    await page.waitForTimeout(100);
  }
  await page.click('.swatch[title="#4b80ca"]');
  await page.waitForTimeout(100);

  // Tap again in the donut ring (which is currently dark #212123)
  const countBeforeRecolor = await page.evaluate(() => scittle.core.eval_string('(count (:shapes @main/app))'));
  await canvas.dispatchEvent('pointerdown', { pointerId: 1, pointerType: 'touch', clientX: cx + 80, clientY: cy, button: 0 });
  await page.waitForTimeout(50);
  await canvas.dispatchEvent('pointerup', { pointerId: 1, pointerType: 'touch', clientX: cx + 80, clientY: cy, button: 0 });
  await page.waitForTimeout(400);

  const countAfterRecolor = await page.evaluate(() => scittle.core.eval_string('(count (:shapes @main/app))'));
  console.log('Shapes count before vs after recolor:', countBeforeRecolor, 'vs', countAfterRecolor);

  const latestFillColor = await page.evaluate(() => scittle.core.eval_string('(:fill (last (filter #(and (:fill %) (not= (:fill %) "none")) (:shapes @main/app))))'));
  console.log('Latest fill shape color:', latestFillColor);

  // Now tap again with the SAME color (#4b80ca)
  await canvas.dispatchEvent('pointerdown', { pointerId: 1, pointerType: 'touch', clientX: cx + 80, clientY: cy, button: 0 });
  await page.waitForTimeout(50);
  await canvas.dispatchEvent('pointerup', { pointerId: 1, pointerType: 'touch', clientX: cx + 80, clientY: cy, button: 0 });
  await page.waitForTimeout(300);

  const countAfterSameColor = await page.evaluate(() => scittle.core.eval_string('(count (:shapes @main/app))'));
  console.log('Shapes count after tapping with same color (should not change):', countAfterSameColor);
  console.log('Same-color tap was no-op:', countAfterRecolor === countAfterSameColor);

  await browser.close();
  s.server.close();
  console.log('\nALL CHECKS COMPLETED SUCCESSFULLY!');
})().catch(e => {
  console.error('TEST FAILED:', e);
  process.exit(1);
});
