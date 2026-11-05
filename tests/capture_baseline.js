// Phase 0: capture baseline screenshots of the existing tldraw app.
// Run: node tests/capture_baseline.js
const { chromium } = require('/root/pwtest/node_modules/playwright');
const path = require('path');

const OUT = path.join(__dirname, 'baseline-screenshots');
const URL = process.env.BASE_URL || 'http://localhost:8000/index.html';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));

  await page.goto(URL, { waitUntil: 'load', timeout: 60000 });
  // Wait for tldraw ready and editor to mount.
  await page.waitForFunction(() => !!window.editor, null, { timeout: 60000 });
  await page.waitForTimeout(2500);

  // 1. Initial canvas
  await page.screenshot({ path: path.join(OUT, '01-initial-canvas.png') });
  console.log('captured 01-initial-canvas.png');

  // 2. Style panel with palette swatches (open the palette selector if collapsed)
  const paletteTrigger = page.locator('text=CC-29').first();
  try {
    await paletteTrigger.click({ timeout: 3000 });
    await page.waitForTimeout(600);
  } catch (e) {
    console.log('palette trigger click skipped:', e.message);
  }
  await page.screenshot({ path: path.join(OUT, '02-style-panel-palette.png') });
  console.log('captured 02-style-panel-palette.png');
  // close any open popover
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // 3. Drawn stroke
  await page.mouse.move(420, 380);
  await page.mouse.down();
  for (let i = 0; i < 40; i++) {
    const t = i / 39;
    await page.mouse.move(420 + t * 420, 380 + Math.sin(t * Math.PI * 2) * 90);
    await page.waitForTimeout(12);
  }
  await page.mouse.up();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(OUT, '03-drawn-stroke.png') });
  console.log('captured 03-drawn-stroke.png');

  // 4. Pinterest overlay
  try {
    await page.locator('button:has-text("Ref")').first().click({ timeout: 4000 });
    await page.waitForTimeout(2500);
    await page.screenshot({ path: path.join(OUT, '04-pinterest-overlay.png') });
    console.log('captured 04-pinterest-overlay.png');
  } catch (e) {
    console.log('pinterest overlay capture failed:', e.message);
    await page.screenshot({ path: path.join(OUT, '04-pinterest-overlay.png') });
  }

  await browser.close();
  console.log('baseline capture complete');
})();
