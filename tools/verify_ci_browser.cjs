// Run with the repository's locked Playwright after its formal system install.
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const webRequire = createRequire(path.resolve('apps/web/package.json'));
const { chromium } = webRequire('playwright');

async function main() {
  const output = path.resolve(process.argv[2]);
  fs.mkdirSync(output, { recursive: true });
  const result = { status: 'BLOCKED', browserTests: 'NOT_RUN',
    output,
    playwright: webRequire('playwright/package.json').version,
    os: fs.readFileSync('/etc/os-release', 'utf8') };
  let browser;
  try {
    if (result.playwright !== '1.63.0' || !/^ID=ubuntu$/m.test(result.os)
        || !/^VERSION_ID="24\.04"$/m.test(result.os)) {
      throw new Error('Unexpected browser preparation platform/version');
    }
    browser = await chromium.launch(); // actual default headless binary used by tests
    result.chromium = browser.version();
    const page = await browser.newPage();
    await page.setContent(`<meta charset="utf-8"><style>
      body { font-family: "Noto Sans CJK SC"; font-size: 18px; }
      h1 { font-weight: 700; } p { font-weight: 400; }
      </style><h1>合成演示：中文粗体报告</h1>
      <p>执行记录、偏差复测、附件版本与模拟签署。末页完整性检查。</p>`);
    await page.evaluate(() => document.fonts.ready);
    const session = await page.context().newCDPSession(page);
    await session.send('DOM.enable');
    await session.send('CSS.enable');
    const { root } = await session.send('DOM.getDocument');
    result.renderedFonts = {};
    for (const [tag, expected] of [['h1', 'NotoSansCJKsc-Bold'], ['p', 'NotoSansCJKsc-Regular']]) {
      const { nodeId } = await session.send('DOM.querySelector', { nodeId: root.nodeId, selector: tag });
      const { fonts } = await session.send('CSS.getPlatformFontsForNode', { nodeId });
      if (!fonts.some(font => font.familyName === 'Noto Sans CJK SC'
          && font.postScriptName === expected && font.glyphCount > 0)) {
        throw new Error('Chromium did not use the verified Chinese face');
      }
      result.renderedFonts[tag] = fonts;
    }
    await page.pdf({ path: path.join(output, 'chinese-preflight.pdf'), format: 'A4', printBackground: true });
    await page.screenshot({ path: path.join(output, 'chinese-preflight.png') });
    result.status = 'READY';
    delete result.browserTests;
  } catch (error) {
    result.error = error.message;
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    fs.writeFileSync(path.join(output, 'chromium.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  }
}
main().catch(error => {
  console.error('Chromium preparation failed before evidence could be finalized:', error.name);
  process.exitCode = 1;
});
