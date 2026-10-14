const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/example/MainActivity.kt', 'utf8')
  .split('val DISTRACTION_FREE_INJECTION_JS = """')[1].split('""".trimIndent()')[0];

function page(path = '/direct/t/123/', options = {}) {
  const documentListeners = {}, windowListeners = {}, hidden = new Set(), calls = [];
  // Instagram can render discovery controls in generic divs, without a nav ancestor.
  const shortcut = {closest: () => null, style: {setProperty: () => hidden.add('shortcut')}};
  const document = {
    documentElement: {toggleAttribute() {}},
    getElementById: () => true,
    querySelectorAll: (selector) => selector === 'a[href*="/reel/"]'
      ? [{closest: () => ({style: {setProperty: () => hidden.add('home-reel')}})}]
      : selector.startsWith('a[href') ? [shortcut] : (options.textNodes || []),
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

test('preserves followed posts with Reel permalinks instead of emptying home', () => {
  assert(!page('/').hidden.has('home-reel'));
  assert(!page('/direct/t/123/').hidden.has('home-reel'));
  assert(!page('/reel/shared123/').hidden.has('home-reel'));
});


test('suggestion heading outside a post never hides the feed or its parent', () => {
  let feedHidden = false;
  const feed = {style: {setProperty: () => feedHidden = true}};
  const heading = {children: [], textContent: 'Suggested for you', parentElement: feed,
    closest: selector => selector === 'article' ? null : feed};
  page('/', {textNodes: [heading]});
  assert.equal(feedHidden, false);
});

test('hides a single suggested article, leaving the surrounding feed intact', () => {
  let postHidden = false, feedHidden = false;
  const feed = {style: {setProperty: () => feedHidden = true}};
  const post = {querySelector: () => null, style: {setProperty: () => postHidden = true}};
  const heading = {children: [], textContent: 'Suggested for you', parentElement: feed,
    closest: selector => selector === 'article' ? post : feed};
  page('/', {textNodes: [heading]});
  assert.equal(postHidden, true);
  assert.equal(feedHidden, false);
});

test('preserves article wrappers containing other articles or app navigation', () => {
  for (const child of ['article', 'nav']) {
    let hidden = false;
    const wrapper = {querySelector: selector => selector.includes(child) ? {} : null,
      style: {setProperty: () => hidden = true}};
    const heading = {children: [], textContent: 'Suggested for you', closest: () => wrapper};
    page('/', {textNodes: [heading]});
    assert.equal(hidden, false);
  }
});

test('ordinary post text mentioning suggestions does not hide the post', () => {
  let hidden = false;
  const post = {querySelector: () => null, style: {setProperty: () => hidden = true}};
  const caption = {children: [], textContent: 'I have suggestions for you today', closest: () => post};
  page('/', {textNodes: [caption]});
  assert.equal(hidden, false);
});
