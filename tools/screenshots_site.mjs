// Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
// All rights reserved. Proprietary and confidential - see LICENSE.
// SPDX-License-Identifier: LicenseRef-Proprietary

/**
 * Temporary site screenshot matrix + front-end smoke test.
 *
 * Renders all seven generated pages (six locales + the 404) at three viewports in a real
 * Chromium, saves a full-page PNG per combination into `build/site-matrix/`, and fails the run
 * when a page shows a defect a static check cannot see:
 *
 *   * a JavaScript error or a failed request (a broken asset path after a move),
 *   * a broken image (an `alt` on an empty box looks fine in HTML and terrible on a phone),
 *   * a horizontal overflow (the classic mobile-landing bug: one long headline pushes the page),
 *   * zero or more than one `<h1>`, an empty `<title>`, or a missing `description`.
 *
 * It is deliberately *not* part of the release gate: the matrix is for reviewing the UI, and
 * golden-image comparisons on a marketing page would fail on every copy change.
 *
 * Usage:  node tools/screenshots_site.mjs [--out build/site-matrix]
 * Requires `playwright` (`npm i playwright && npx playwright install --with-deps chromium`).
 */

import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { extname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const PAGES = [
  ['en', 'docs/index.html'],
  ['de', 'docs/de/index.html'],
  ['es', 'docs/es/index.html'],
  ['fr', 'docs/fr/index.html'],
  ['it', 'docs/it/index.html'],
  ['pl', 'docs/pl/index.html'],
  ['404', 'docs/404.html'],
];

const VIEWPORTS = [
  ['mobile', 390, 844],
  ['tablet', 834, 1112],
  ['desktop', 1440, 900],
];

const outIndex = process.argv.indexOf('--out');
const OUT = resolve(outIndex > 0 ? process.argv[outIndex + 1] : 'build/site-matrix');

const findings = [];
const ORIGIN = 'https://andrexthedev.github.io/ConstructAR/';

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json',
  '.xml': 'application/xml',
  '.txt': 'text/plain; charset=utf-8',
};

/**
 * Serve the published origin from `docs/`.
 *
 * The pages are opened as `file://`, but the 404 page *must* use absolute URLs (GitHub Pages
 * serves it at whatever path the visitor asked for) and a browser blocks those on a file://
 * document. Intercepting the origin does two things at once: the absolute URLs resolve, and a
 * URL that points at nothing becomes a finding instead of a silently empty box.
 */
async function routeOrigin(page) {
  await page.route(`${ORIGIN}**`, async (route) => {
    const requested = new URL(route.request().url());
    const relative = decodeURIComponent(requested.pathname.replace(/^\/ConstructAR\//, ''));
    const candidate = relative === '' || relative.endsWith('/') ? `${relative}index.html` : relative;
    const file = resolve('docs', candidate);
    if (existsSync(file)) {
      await route.fulfill({ path: file, contentType: CONTENT_TYPES[extname(file)] ?? 'application/octet-stream' });
    } else {
      findings.push(`- **assets**: the site links to \`${requested.pathname}\`, which is not in docs/`);
      await route.fulfill({ status: 404, body: 'missing' });
    }
  });
}

function fail(where, message) {
  findings.push(`- **${where}**: ${message}`);
}

const browser = await chromium.launch();

for (const [locale, file] of PAGES) {
  const url = pathToFileURL(resolve(file)).href;
  for (const [device, width, height] of VIEWPORTS) {
    const where = `${locale}/${device}`;
    // One device pixel per CSS pixel: these are review images, not print assets, and 2x
    // doubled the artifact to 28 MB a run for no extra information.
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await routeOrigin(page);

    const consoleErrors = [];
    page.on('pageerror', (error) => consoleErrors.push(error.message));
    page.on('requestfailed', (request) => consoleErrors.push(`${request.url()} -> ${request.failure()?.errorText}`));

    const response = await page.goto(url, { waitUntil: 'load' });
    if (response && response.status() >= 400) {
      fail(where, `HTTP ${response.status()}`);
    }
    await page.evaluate(() => document.fonts?.ready);
    await page.waitForTimeout(120);

    const audit = await page.evaluate(() => {
      const images = [...document.images].map((image) => ({
        src: image.getAttribute('src'),
        broken: image.complete && image.naturalWidth === 0,
        // `alt=""` marks a decorative image next to a text label and is correct; a *missing*
        // attribute is what makes a screen reader read out a file name.
        hasAlt: image.hasAttribute('alt'),
      }));
      const headings = [...document.querySelectorAll('h1')].map((h) => h.textContent?.trim() ?? '');
      return {
        title: document.title ?? '',
        description: document.querySelector('meta[name="description"]')?.content ?? '',
        h1: headings,
        images,
        overflow: document.documentElement.scrollWidth - window.innerWidth,
        widest: (() => {
          const limit = window.innerWidth;
          let worst = null;
          for (const element of document.querySelectorAll('body *')) {
            const box = element.getBoundingClientRect();
            if (box.width === 0) continue;
            const over = Math.round(box.right - limit);
            if (over > 1 && (!worst || over > worst.over)) {
              worst = {
                over,
                tag: element.tagName.toLowerCase(),
                cls: element.className && typeof element.className === 'string' ? element.className : '',
                text: (element.textContent ?? '').trim().slice(0, 60),
              };
            }
          }
          return worst;
        })(),
        height: document.documentElement.scrollHeight,
        links: document.querySelectorAll('a[href]').length,
      };
    });

    if (consoleErrors.length > 0) fail(where, `console/request errors: ${consoleErrors.join('; ')}`);
    if (!audit.title) fail(where, 'empty <title>');
    if (!audit.description) fail(where, 'empty meta description');
    if (audit.h1.length !== 1) fail(where, `expected exactly one <h1>, found ${audit.h1.length}`);
    if (locale !== '404' && audit.h1[0] && audit.h1[0].length < 10) fail(where, `<h1> is suspiciously short: "${audit.h1[0]}"`);
    if (audit.overflow > 1) {
      const worst = audit.widest;
      const culprit = worst
        ? ` — widest culprit: <${worst.tag} class="${worst.cls}"> sticks out ${worst.over}px ("${worst.text}")`
        : '';
      fail(where, `horizontal overflow of ${audit.overflow}px${culprit}`);
    }
    for (const image of audit.images) {
      if (image.broken) fail(where, `broken image ${image.src}`);
      if (!image.hasAlt) fail(where, `image without an alt attribute: ${image.src}`);
    }

    mkdirSync(resolve(OUT, locale), { recursive: true });
    await page.screenshot({ path: resolve(OUT, locale, `${device}.png`), fullPage: true });
    console.log(
      `${where.padEnd(16)} ${String(audit.height).padStart(5)}px tall · ${audit.links} links · ` +
        `${audit.images.length} images · title ${audit.title.length} chars`,
    );
    await page.close();
  }
}

await browser.close();

const report = [
  '# Site screenshot matrix',
  '',
  `Generated ${new Date().toISOString()} for ${PAGES.length} pages × ${VIEWPORTS.length} viewports.`,
  '',
  findings.length === 0 ? 'No front-end findings.' : `## Findings (${findings.length})\n\n${findings.join('\n')}`,
  '',
].join('\n');
mkdirSync(OUT, { recursive: true });
writeFileSync(resolve(OUT, 'report.md'), report);

if (findings.length > 0) {
  console.error(`\n${findings.length} finding(s):\n${findings.join('\n')}`);
  process.exit(1);
}
console.log(`\nOK — screenshots in ${OUT}`);
