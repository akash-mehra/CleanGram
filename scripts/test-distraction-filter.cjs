const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/example/MainActivity.kt', 'utf8')
  .split('val DISTRACTION_FREE_INJECTION_JS = """')[1].split('""".trimIndent()')[0];

function page(path = '/direct/t/123/') {
  const documentListeners = {}, windowListeners = {}, hidden = new Set(), calls = [];
  // Instagram can render discovery controls in generic divs, without a nav ancestor.
  const shortcut = {closest: () => null, style: {setProperty: () => hidden.add('shortcut')}};
  const document = {
    documentElement: {toggleAttribute() {}},
    getElementById: () => true,
    querySelectorAll: (selector) => selector === 'a[href*="/reel/"]'
      ? [{closest: () => ({style: {setProperty: () => hidden.add('home-reel')}})}]
      : selector.startsWith('a[href') ? [shortcut] : [],
    addEventListener: (event, callback) => documentListeners[event] = callback,
  };
  const location = {
    origin: 'https://www.instagram.com', pathname: path,
    get href() {return this.origin + this.pathname;},
    replace(url) {calls.push(['redirect', url]); this.pathname = url;},
  };
  const history = {};
  for (const method of ['pushState', 'replaceState']) {
    history[method] = (state, title, url) => {
      calls.push([method, url]);
      if (url != null) location.pathname = new URL(url, location.href).pathname;
    };
  }
  const context = vm.createContext({document, location, history, URL,
    window: {addEventListener: (event, callback) => windowListeners[event] = callback},
    MutationObserver: class {observe() {}}, requestAnimationFrame: callback => callback(),
  });
  vm.runInContext(source, context);
  return {context, location, history, calls, hidden, documentListeners, windowListeners};
}

test('hides discovery shortcuts rendered outside semantic navigation', () => {
  assert(page().hidden.has('shortcut'));
});

test('blocks JavaScript router navigation to Reels and Explore feeds', () => {
  for (const method of ['pushState', 'replaceState']) {
    for (const path of ['/reels', '/reels/', '/reels/123/', '/explore/', '/explore/tags/test/']) {
      const p = page(); p.history[method]({}, '', path);
      assert.equal(p.calls.length, 0, `${method}: ${path}`);
      assert.equal(p.location.pathname, '/direct/t/123/');
    }
  }
});

test('allows individual shared posts, messages, and profiles', () => {
  for (const path of ['/reel/shared123/', '/p/shared123/', '/direct/t/456/', '/explore_user/']) {
    const p = page(); p.history.pushState({}, '', path);
    assert.equal(p.location.pathname, path);
    assert.equal(p.calls.length, 1);
  }
});

test('redirects a discovery route on first injection or browser Back', () => {
  for (const path of ['/reels/', '/explore/']) {
    const initial = page(path); assert.equal(initial.location.pathname, '/');
    const back = page(); back.location.pathname = path; back.windowListeners.popstate();
    assert.equal(back.location.pathname, '/');
  }
});

test('blocks discovery link clicks but preserves shared Reel clicks', () => {
  for (const [path, blocked] of [['/reels/', true], ['/explore/', true], ['/reel/shared123/', false]]) {
    const p = page(); let prevented = false, stopped = false;
    p.documentListeners.click({target: {closest: () => ({href: p.location.origin + path})},
      preventDefault: () => prevented = true, stopImmediatePropagation: () => stopped = true});
    assert.equal(prevented, blocked); assert.equal(stopped, blocked);
  }
});

test('hides Reel posts in the home feed while keeping DM attachments intact', () => {
  assert(page('/').hidden.has('home-reel'));
  assert(!page('/direct/t/123/').hidden.has('home-reel'));
  assert(!page('/reel/shared123/').hidden.has('home-reel'));
});
