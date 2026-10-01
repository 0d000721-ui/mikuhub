// Run with Node 18+ and the JSON emitted by BrowserImageScriptHarnessKt.
// fetch uses a real local HTTP server; only the public URL-to-local transport is substituted.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import vm from 'node:vm';
import { test } from 'node:test';

const scripts = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(120_000, 3)]);
let handler;
const server = http.createServer((req, res) => handler(req, res));
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;

function page(origin = 'https://chatgpt.com', blobBytes = png, blobMime = 'image/png', { blockBlobFetch = false } = {}) {
  class FileReader {
    async readAsDataURL(blob) {
      try { this.result = `data:${blob.type};base64,${Buffer.from(await blob.arrayBuffer()).toString('base64')}`; this.onload?.(); }
      catch { this.onerror?.(); }
    }
  }
  const publicBlob = 'blob:https://chatgpt.com/image-id';
  const blobHandles = new Map([[publicBlob, URL.createObjectURL(new Blob([blobBytes], { type: blobMime }))]]);
  let createdCount = 0;
  class PageURL extends URL {
    static createObjectURL(blob) {
      const source = ++createdCount === 1 ? publicBlob : `${publicBlob}-${createdCount}`;
      if (blobHandles.has(source)) URL.revokeObjectURL(blobHandles.get(source));
      blobHandles.set(source, URL.createObjectURL(blob));
      return source;
    }
  }
  const originalCreateObjectURL = PageURL.createObjectURL;
  const listeners = new Map();
  class HTMLAnchorElement {
    href = 'blob:https://chatgpt.com/image-id';
    hasAttribute(name) { return name === 'download'; }
    closest() { return this; }
    click() { URL.revokeObjectURL(blobHandles.get(this.href)); }
  }
  const document = {
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(fn); },
    removeEventListener(type, fn) { listeners.get(type)?.delete(fn); },
  };
  let options;
  const context = vm.createContext({ Blob, URL: PageURL, FileReader, AbortController, Uint8Array, setTimeout, clearTimeout, document, HTMLAnchorElement,
    location: { origin },
    fetch: async (url, init) => {
      options = init;
      if (blobHandles.has(url)) {
        if (blockBlobFetch) throw new TypeError('Browser rejected Blob URL fetch');
        // Node/undici aborting a Blob response can throw an internal exception. HTTP uses
        // the real AbortSignal below; Blob tests exercise real revoke/stream behavior.
        return fetch(blobHandles.get(url), { ...init, signal: undefined });
      }
      assert.ok(['https://chatgpt.com/image-test', 'https://files.oaiusercontent.com/image-test'].includes(url));
      return fetch(`${base}/image-test`, init);
    },
  });
  context.window = context;
  context.top = context;
  return { run: script => JSON.parse(vm.runInContext(script, context)), options: () => options,
    gesture: () => { for (const fn of listeners.get('pointerdown') || []) fn({ isTrusted: true }); },
    clickAnchor: () => new HTMLAnchorElement().click(),
    createDownloadBlob: (bytes = blobBytes, mime = blobMime) => PageURL.createObjectURL(new Blob([bytes], { type: mime })),
    createHookRestored: () => PageURL.createObjectURL === originalCreateObjectURL,
    replaceCreateHook: () => { const replacement = blob => originalCreateObjectURL(blob); PageURL.createObjectURL = replacement; return () => PageURL.createObjectURL === replacement; },
    close: () => { for (const handle of blobHandles.values()) URL.revokeObjectURL(handle); },
  };
}

async function collect(browser) {
  const chunks = [];
  for (let i = 0; i < 1500; i++) {
    const result = browser.run(scripts.poll);
    if (result.status === 'error' || result.status === 'complete') return { result, chunks };
    if (result.status === 'chunk') {
      assert.equal(result.offset, chunks.reduce((size, value) => size + value.length, 0));
      const chunk = Buffer.from(result.data, 'base64');
      assert.ok(chunk.length <= scripts.chunkBytes);
      assert.equal(result.mime, 'image/png');
      chunks.push(chunk);
    }
    await new Promise(resolve => setTimeout(resolve, 2));
  }
  throw new Error('Download did not terminate');
}

try {
  await test('a sized but empty SPA container is not proof of rendered content', () => {
    const container = { tagName: 'DIV', childNodes: [], getBoundingClientRect: () => ({ width: 200, height: 400 }) };
    Object.defineProperty(container, 'textContent', { get() { throw new Error('Must not read page text'); } });
    const context = vm.createContext({ document: { body: { querySelectorAll: () => [container], childNodes: [] } }, getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' }) });
    const counts = JSON.parse(vm.runInContext(scripts.renderCounts, context));
    assert.equal(counts.visible, 0);
  });
  await test('visible controls count without reading their values, hidden controls do not', () => {
    const button = { tagName: 'BUTTON', childNodes: [], getBoundingClientRect: () => ({ width: 80, height: 30 }) };
    Object.defineProperty(button, 'value', { get() { throw new Error('Must not read form values'); } });
    for (const [opacity, expected] of [['1', 1], ['0', 0]]) {
      const context = vm.createContext({ document: { body: { querySelectorAll: () => [button], childNodes: [] } }, getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity }) });
      assert.equal(JSON.parse(vm.runInContext(scripts.renderCounts, context)).visible, expected);
    }
  });
  await test('text layout rectangles are counted without extracting any page text', () => {
    const text = { nodeType: 3 };
    Object.defineProperty(text, 'data', { get() { throw new Error('Must not read text data'); } });
    const container = { tagName: 'DIV', childNodes: [text], getBoundingClientRect: () => ({ width: 200, height: 40 }) };
    Object.defineProperty(container, 'textContent', { get() { throw new Error('Must not read page text'); } });
    const context = vm.createContext({ document: {
      body: { querySelectorAll: () => [container], childNodes: [] },
      createRange: () => ({ selectNodeContents: node => assert.equal(node, text), getBoundingClientRect: () => ({ width: 80, height: 20 }), detach() {} }),
    }, getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' }) });
    assert.equal(JSON.parse(vm.runInContext(scripts.renderCounts, context)).visible, 1);
  });
  await test('real streaming HTTP image is returned in bounded chunks and cleanup removes it', async () => {
    handler = (_, res) => { res.writeHead(200, { 'Content-Type': 'image/png' }); res.end(png); };
    const browser = page();
    assert.equal(browser.run(scripts.begin).status, 'pending');
    const { result, chunks } = await collect(browser);
    assert.equal(result.status, 'complete');
    assert.equal(result.total, png.length);
    assert.deepEqual(Buffer.concat(chunks), png);
    assert.equal(browser.options().credentials, 'same-origin');
    assert.equal(browser.options().referrerPolicy, 'no-referrer');
    assert.equal(browser.options().redirect, 'error');
    browser.run(scripts.cancel);
    assert.equal(browser.run(scripts.poll).status, 'error');
  });
  await test('HTTP failure and HTML never report a saved image', async () => {
    for (const [status, mime] of [[404, 'image/png'], [200, 'text/html'], [200, 'application/json']]) {
      handler = (_, res) => { res.writeHead(status, { 'Content-Type': mime }); res.end('<html>error</html>'); };
      const browser = page();
      browser.run(scripts.begin);
      assert.equal((await collect(browser)).result.status, 'error');
      browser.run(scripts.cancel);
    }
  });
  await test('oversize images are rejected from their length or from the actual stream', async () => {
    for (const declared of [true, false]) {
      handler = (_, res) => {
        res.writeHead(200, { 'Content-Type': 'image/png', ...(declared ? { 'Content-Length': scripts.maxBytes + 1 } : {}) });
        res.end(Buffer.alloc(scripts.maxBytes + 1));
      };
      const browser = page();
      browser.run(scripts.begin);
      const { result, chunks } = await collect(browser);
      assert.equal(result.status, 'error');
      assert.equal(chunks.length, 0);
      browser.run(scripts.cancel);
    }
  });
  await test('only the official top-level page can initiate the image transfer', async () => {
    const browser = page('https://evil.test');
    assert.equal(browser.run(scripts.begin).status, 'error');
    assert.equal(browser.options(), undefined);
    const official = page();
    assert.equal(official.run(scripts.badSource).status, 'error');
    assert.equal(official.options(), undefined);
  });
  await test('a real Blob is transferred and official CDN fetch omits browser credentials', async () => {
    handler = (_, res) => { res.writeHead(200, { 'Content-Type': 'image/png' }); res.end(png); };
    for (const [script, credentials] of [[scripts.blob, 'same-origin'], [scripts.cdn, 'omit']]) {
      const browser = page();
      browser.run(script);
      const { result, chunks } = await collect(browser);
      assert.equal(result.status, 'complete');
      assert.deepEqual(Buffer.concat(chunks), png);
      assert.equal(browser.options().credentials, credentials);
      browser.run(scripts.cancel);
      browser.close();
    }
  });
  await test('revoking before the native callback fails without early retention', async () => {
    const browser = page();
    browser.clickAnchor();
    browser.run(scripts.blob);
    assert.equal((await collect(browser)).result.status, 'error');
    browser.run(scripts.cancel);
  });
  await test('trusted download click retains the real Blob before immediate revoke and delayed native fetch', async () => {
    const browser = page();
    browser.run(scripts.install);
    browser.gesture();
    browser.clickAnchor();
    await new Promise(resolve => setTimeout(resolve, 40));
    browser.run(scripts.cachedBlob);
    const { result, chunks } = await collect(browser);
    assert.equal(result.status, 'complete');
    assert.deepEqual(Buffer.concat(chunks), png);
    browser.run(scripts.cancel);
    browser.run(scripts.uninstall);
  });
  await test('download reads its retained Blob when the browser rejects Blob URL fetch', async () => {
    const browser = page('https://chatgpt.com', png, 'image/png', { blockBlobFetch: true });
    try {
      browser.run(scripts.install);
      browser.gesture();
      browser.createDownloadBlob();
      browser.clickAnchor(); // The website immediately revokes the URL after starting download.
      browser.run(scripts.cachedBlob);
      const { result, chunks } = await collect(browser);
      assert.equal(result.status, 'complete');
      assert.deepEqual(Buffer.concat(chunks), png);
      assert.equal(browser.options(), undefined, 'A retained in-page Blob must not cause a second fetch');
    } finally {
      browser.run(scripts.cancel);
      browser.run(scripts.uninstall);
      assert.equal(browser.createHookRestored(), true);
      browser.close();
    }
  });
  await test('retaining a newly created Blob still requires a trusted gesture or an armed AI action', async () => {
    for (const armed of [false, true]) {
      const browser = page('https://chatgpt.com', png, 'image/png', { blockBlobFetch: true });
      try {
        browser.run(scripts.install);
        if (armed) browser.run(scripts.arm);
        browser.createDownloadBlob();
        browser.clickAnchor();
        browser.run(scripts.cachedBlob);
        assert.equal((await collect(browser)).result.status, armed ? 'complete' : 'error');
      } finally { browser.run(scripts.cancel); browser.run(scripts.uninstall); browser.close(); }
    }
  });
  await test('retained Blob MIME parameters receive the same normalization as fetched images', async () => {
    const browser = page('https://chatgpt.com', png, 'image/png; charset=binary', { blockBlobFetch: true });
    try {
      browser.run(scripts.install);
      browser.gesture();
      browser.createDownloadBlob();
      browser.clickAnchor();
      browser.run(scripts.cachedBlob);
      assert.equal((await collect(browser)).result.status, 'complete');
      assert.equal(browser.options(), undefined);
    } finally { browser.run(scripts.cancel); browser.run(scripts.uninstall); browser.close(); }
  });
  await test('raw Blob retention enforces both the object count and the total byte limit', async () => {
    for (const byteLimit of [false, true]) {
      const browser = page('https://chatgpt.com', png, 'image/png', { blockBlobFetch: true });
      try {
        browser.run(scripts.install);
        browser.gesture();
        const bytes = byteLimit ? Buffer.alloc(scripts.maxBytes / 2 + 1) : png;
        browser.createDownloadBlob(bytes);
        for (let i = 0; i < (byteLimit ? 1 : 4); i++) browser.createDownloadBlob(bytes);
        browser.clickAnchor(); // The oldest source was evicted; fetching it remains disallowed.
        browser.run(scripts.cachedBlob);
        const { result, chunks } = await collect(browser);
        assert.equal(result.status, 'error');
        assert.equal(chunks.length, 0);
      } finally { browser.run(scripts.cancel); browser.run(scripts.uninstall); browser.close(); }
    }
  });
  await test('stopping or leaving discards objects retained before the download click', async () => {
    for (const cleanup of [scripts.clear, scripts.uninstall]) {
      const browser = page('https://chatgpt.com', png, 'image/png', { blockBlobFetch: true });
      try {
        browser.run(scripts.install);
        browser.gesture();
        browser.createDownloadBlob();
        browser.run(cleanup);
        browser.clickAnchor();
        browser.run(scripts.cachedBlob);
        assert.equal((await collect(browser)).result.status, 'error');
      } finally { browser.run(scripts.cancel); browser.run(scripts.uninstall); browser.close(); }
    }
  });
  await test('uninstall does not overwrite a newer website createObjectURL handler', () => {
    const browser = page();
    browser.run(scripts.install);
    const stillWebsiteHook = browser.replaceCreateHook();
    browser.run(scripts.uninstall);
    assert.equal(stillWebsiteHook(), true);
    browser.close();
  });
  await test('untrusted anchor cannot retain an image until an AI action is armed', async () => {
    for (const armed of [false, true]) {
      const browser = page();
      browser.run(scripts.install);
      if (armed) browser.run(scripts.arm);
      browser.clickAnchor();
      browser.run(scripts.cachedBlob);
      assert.equal((await collect(browser)).result.status, armed ? 'complete' : 'error');
      browser.run(scripts.cancel);
      browser.run(scripts.uninstall);
    }
  });
  await test('early retention remains bounded and rejects unsupported Blob MIME types', async () => {
    for (const [bytes, mime] of [[Buffer.alloc(scripts.maxBytes + 1), 'image/png'], [Buffer.from('<html>error</html>'), 'text/html']]) {
      const browser = page('https://chatgpt.com', bytes, mime);
      browser.run(scripts.install);
      browser.gesture();
      browser.clickAnchor();
      browser.run(scripts.cachedBlob);
      const { result, chunks } = await collect(browser);
      assert.equal(result.status, 'error');
      assert.equal(chunks.length, 0);
      browser.run(scripts.cancel);
      browser.run(scripts.uninstall);
    }
  });
  await test('permission revocation or document cleanup discards a retained Blob', async () => {
    for (const cleanup of [scripts.clear, scripts.uninstall]) {
      const browser = page();
      browser.run(scripts.install);
      browser.gesture();
      browser.clickAnchor();
      browser.run(cleanup);
      browser.run(scripts.cachedBlob);
      assert.equal((await collect(browser)).result.status, 'error');
      browser.run(scripts.cancel);
      browser.run(scripts.uninstall);
    }
  });
  await test('redirects cannot fetch an unapproved destination', async () => {
    handler = (_, res) => { res.writeHead(302, { Location: 'https://evil.test/image.png' }); res.end(); };
    const browser = page();
    browser.run(scripts.begin);
    assert.equal((await collect(browser)).result.status, 'error');
    browser.run(scripts.cancel);
  });
  await test('cancelling a pending transfer aborts the HTTP request and clears session state', async () => {
    handler = (_, res) => { res.writeHead(200, { 'Content-Type': 'image/png' }); res.write(png.subarray(0, 8)); };
    const browser = page();
    browser.run(scripts.begin);
    await new Promise(resolve => setTimeout(resolve, 30));
    browser.run(scripts.cancel);
    assert.equal(browser.options().signal.aborted, true);
    assert.equal(browser.run(scripts.poll).status, 'error');
  });
} finally {
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}
