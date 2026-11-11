// Automated tests for split toolbars, pan/zoom popout pill, and selection contextual toolbar with z-order.
const playwright = require('playwright');
const { chromium } = playwright;
const assert = require('assert');
const fs = require('fs');

(async () => {
  const executablePath = process.env.CHROMIUM_PATH ||
    ['/usr/bin/chromium-browser', '/usr/bin/chromium', '/usr/bin/google-chrome']
      .find((p) => fs.existsSync(p));

  const browser = await chromium.launch({ executablePath, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const pass = (n) => console.log('PASS', n);

  try {
    const url = process.env.BASE_URL || 'http://localhost:8123/';
    await page.goto(url, { waitUntil: 'load' });
    await page.waitForSelector('.app-root');
    await page.waitForSelector('.canvas-svg');

    // 1. Verify split toolbars exist and have expected layout
    const hasToolsToolbar = await page.$('.tools-toolbar');
    assert(hasToolsToolbar, 'Left-aligned tools-toolbar should exist');

    const hasRhsToolbar = await page.$('.rhs-toolbar');
    assert(hasRhsToolbar, 'RHS toolbar should exist');

    // Tools toolbar has Draw, Eraser, Flood fill, Select
    const toolTitles = await page.$$eval('.tools-toolbar .tool-btn', (btns) => btns.map(b => b.title));
    assert.deepStrictEqual(toolTitles, ['Draw', 'Eraser', 'Flood fill', 'Select'], 'Left toolbar must have draw, eraser, fill, select');
    pass('Left toolbar has draw, eraser, fill, select');

    // RHS toolbar has zoom/pan, undo, redo, palette
    const hasZoomToggle = await page.$('.rhs-toolbar .zoom-toggle-btn');
    const hasUndo = await page.$('.rhs-toolbar button[title="Undo (Ctrl-Z)"]');
    const hasRedo = await page.$('.rhs-toolbar button[title="Redo (Ctrl-Y)"]');
    const hasPalette = await page.$('.rhs-toolbar .palette-toggle-btn');
    assert(hasZoomToggle && hasUndo && hasRedo && hasPalette, 'RHS toolbar must have zoom/pan, undo, redo, palette');
    pass('RHS toolbar has pan/zoom, undo, redo, palette');

    // 2. Pan & zoom together into a pop-up pill
    await page.click('.rhs-toolbar .zoom-toggle-btn');
    await page.waitForSelector('.zoom-popout');
    const hasPanInPopout = await page.$('.zoom-popout .tool-pan-btn');
    assert(hasPanInPopout, 'Pop-up pill must include Pan tool');

    // Click Pan in popout -> should activate pan tool
    await page.click('.zoom-popout .tool-pan-btn');
    await page.waitForTimeout(100);
    const isPanTool = await page.evaluate(() => document.querySelector('.canvas-svg').classList.contains('tool-pan'));
    assert(isPanTool, 'Clicking pan in pop-up should activate pan tool');
    pass('Pan and zoom pop-up pill activates pan tool');

    // 3. Contextual toolbar for selected shapes
    // Switch to Draw tool, draw two strokes
    await page.click('.tools-toolbar button[title="Draw"]');
    // Draw stroke 1
    await page.mouse.move(300, 300);
    await page.mouse.down();
    await page.mouse.move(500, 300, { steps: 5 });
    await page.mouse.up();
    await page.waitForTimeout(100);

    // Draw stroke 2
    await page.mouse.move(300, 400);
    await page.mouse.down();
    await page.mouse.move(500, 400, { steps: 5 });
    await page.mouse.up();
    await page.waitForTimeout(100);

    // Initially when nothing is selected, contextual toolbar is not in DOM
    assert(!(await page.$('.contextual-toolbar')), 'Contextual toolbar should not be shown without selection');

    // Select stroke 1
    await page.click('.tools-toolbar button[title="Select"]');
    await page.mouse.click(400, 300);
    await page.waitForSelector('.contextual-toolbar');
    pass('Contextual toolbar appears when shapes are selected');

    // Verify buttons in contextual toolbar
    const ctxButtons = await page.$$eval('.contextual-toolbar button', (btns) => btns.map(b => b.title));
    assert(ctxButtons.includes('Delete (Del)'), 'Contextual toolbar should have Delete');
    assert(ctxButtons.includes('Duplicate (Ctrl-D)'), 'Contextual toolbar should have Duplicate');
    assert(ctxButtons.includes('Send to back (Shift-[)'), 'Contextual toolbar should have Send to back');
    assert(ctxButtons.includes('Send backward ([)'), 'Contextual toolbar should have Send backward');
    assert(ctxButtons.includes('Bring forward (])'), 'Contextual toolbar should have Bring forward');
    assert(ctxButtons.includes('Bring to front (Shift-])'), 'Contextual toolbar should have Bring to front');
    pass('Contextual toolbar contains Delete, Duplicate, and Z-order buttons');

    // 4. Test Z-Order functionality
    // Stroke 1 is at index 0, Stroke 2 is at index 1
    const getPathY = (idx) => page.evaluate((i) => {
      const ps = document.querySelectorAll('.canvas-svg g.world > path');
      const d = ps[i].getAttribute('d');
      const m = d.match(/M\s+[\d.]+\s+([\d.]+)/);
      return m ? parseFloat(m[1]) : null;
    }, idx);

    assert((await getPathY(0)) === 300, 'Shape at index 0 should initially be stroke 1 (y=300)');
    assert((await getPathY(1)) === 400, 'Shape at index 1 should initially be stroke 2 (y=400)');

    // Bring Stroke 1 forward (or to front) -> index 0 should now be stroke 2 (y=400) and index 1 stroke 1 (y=300)
    await page.click('.contextual-toolbar button[title="Bring to front (Shift-])"]');
    await page.waitForTimeout(100);
    assert((await getPathY(0)) === 400, 'Bring to front: index 0 should now be stroke 2 (y=400)');
    assert((await getPathY(1)) === 300, 'Bring to front: index 1 should now be stroke 1 (y=300)');
    pass('Bring to front reorders shapes in DOM');

    // Send Stroke 1 to back -> index 0 should be stroke 1 (y=300) again
    await page.click('.contextual-toolbar button[title="Send to back (Shift-[)"]');
    await page.waitForTimeout(100);
    assert((await getPathY(0)) === 300, 'Send to back: index 0 should be stroke 1 (y=300)');
    assert((await getPathY(1)) === 400, 'Send to back: index 1 should be stroke 2 (y=400)');
    pass('Send to back reorders shapes in DOM');

    // Test keyboard shortcuts: ']' to bring forward
    await page.keyboard.press(']');
    await page.waitForTimeout(100);
    assert((await getPathY(0)) === 400, 'Key ]: index 0 should now be stroke 2');
    assert((await getPathY(1)) === 300, 'Key ]: index 1 should now be stroke 1');
    pass('Keyboard shortcut ] brings forward');

    // Test keyboard shortcuts: '[' to send backward
    await page.keyboard.press('[');
    await page.waitForTimeout(100);
    assert((await getPathY(0)) === 300, 'Key [: index 0 should be stroke 1');
    assert((await getPathY(1)) === 400, 'Key [: index 1 should be stroke 2');
    pass('Keyboard shortcut [ sends backward');

    // Deselect -> contextual toolbar disappears
    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);
    assert(!(await page.$('.contextual-toolbar')), 'Contextual toolbar disappears on deselect');
    pass('Contextual toolbar disappears on deselect');

    console.log('\nALL UI TOOLBAR TESTS PASSED');
  } finally {
    await browser.close();
  }
})();
