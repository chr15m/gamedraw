const { chromium } = require('/root/pwtest/node_modules/playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  p.on('pageerror', (e) => console.log('[pageerror]', e.message));
  p.on('console', (m) => console.log('[console]', m.type(), m.text()));
  await p.goto('http://localhost:8000/probe.html', { waitUntil: 'load', timeout: 60000 });
  await p.waitForTimeout(4000);
  const html = await p.evaluate(() => document.getElementById('app').innerHTML);
  console.log('APP HTML:', html);
  await p.click('#inc');
  await p.waitForTimeout(300);
  console.log('AFTER CLICK:', await p.evaluate(() => document.getElementById('app').innerText));
  await b.close();
})();
