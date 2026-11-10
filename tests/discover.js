// Discovery: dump interactive elements in the existing tldraw app.
let playwright;
try { playwright = require('playwright'); } catch (e) { playwright = require('/root/pwtest/node_modules/playwright'); }
const { chromium } = playwright;

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('console', (m) => console.log('[console]', m.type(), m.text()));
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));
  await page.goto('http://localhost:8000/index.html', { waitUntil: 'load', timeout: 60000 });
  // Wait for the app to mount
  await page.waitForTimeout(8000);
  const info = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('button')].map((b) => ({
      text: (b.innerText || '').trim().slice(0, 40),
      title: b.title || b.getAttribute('aria-label') || '',
      cls: b.className.toString().slice(0, 60),
    }));
    return {
      title: document.title,
      hasEditor: !!window.editor,
      bodyText: document.body.innerText.slice(0, 500),
      buttons: btns.slice(0, 60),
    };
  });
  console.log(JSON.stringify(info, null, 2));
  await page.screenshot({ path: 'tests/baseline-screenshots/_discover.png', fullPage: true });
  await browser.close();
})();
