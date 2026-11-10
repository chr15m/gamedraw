// Load the new SVG app and report errors + a screenshot.
let playwright;
try { playwright = require('playwright'); } catch (e) { playwright = require('/root/pwtest/node_modules/playwright'); }
const { chromium } = playwright;
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') console.log('[console]', m.type(), m.text()); });
  page.on('pageerror', (e) => { errors.push(e.message); console.log('[pageerror]', e.message); });
  await page.goto('http://localhost:8000/index.html', { waitUntil: 'load', timeout: 60000 });
  await page.waitForTimeout(4000);
  const info = await page.evaluate(() => ({
    hasSvg: !!document.querySelector('.canvas-svg'),
    hasToolbar: !!document.querySelector('.toolbar'),
    hasStylePanel: !!document.querySelector('.style-panel'),
    swatches: document.querySelectorAll('.swatch').length,
    buttons: document.querySelectorAll('button').length,
    bodyText: document.body.innerText.replace(/\n/g, ' | '),
  }));
  console.log(JSON.stringify(info, null, 2));
  await page.screenshot({ path: 'tests/baseline-screenshots/new-01-initial.png' });
  console.log('errors:', errors.length);
  await browser.close();
})();
