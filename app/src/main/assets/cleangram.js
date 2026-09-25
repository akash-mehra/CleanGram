// CleanGram page filter. Registered to run at document start on every Instagram page, so it is
// in place before Instagram's own scripts and survives in-app (history.pushState) navigation.
//
// Design rules, learned the hard way:
//  - Never hide a container that could hold someone's post, the feed, or the stories tray.
//    Hiding the nearest <section> once took the whole feed and the stories tray with it.
//  - Only look at what changed. Each pass inspects the nodes added since the last pass, and
//    passes are batched to one per animation frame, so hidden content is gone before it is
//    ever painted instead of popping in and then vanishing.
(function () {
  'use strict';
  if (window.__cleangram) return;
  window.__cleangram = true;

  var STYLE_ID = 'cleangram-style';

  // The Reels tab. "/reels/" is plural; a followed account's reel post links to "/reel/<id>/"
  // and must stay visible, as must usernames that merely start with "reels". CSS hides the link
  // from the very first paint; the pass below then collapses the slot it sat in.
  var REELS_LINK = 'a[href="/reels/"],a[href^="/reels/"],a[href^="/reels?"]';
  var CSS = REELS_LINK + '{display:none!important}';

  var APP_PROMPT = /^(open app|open in app|use the app|get the app|open instagram|use app)$/;
  var SUGGESTED = /^suggested (for you|posts|reels|accounts)$/;
  var SUGGESTED_IN_HEADER = /suggested (for you|posts|reels)/;
  var CONTROL = 'a, button, [role="button"]';
  // Anything that marks a region as someone's content: a post, a story ring, a story button.
  var PROTECTED = 'article, canvas, [aria-label*="story" i], [aria-label*="Story"]';

  var pending = [];
  var readySent = false;

  function addStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = CSS;
    root.appendChild(style);
  }

  function hide(el) {
    el.style.setProperty('display', 'none', 'important');
  }

  function textOf(el) {
    return (el.textContent || '').trim().toLowerCase();
  }

  // Climb through wrappers that contain nothing but this element, so hiding it also collapses
  // the slot it occupied (a nav bar keeps an empty gap otherwise). Stops at the first ancestor
  // holding anything else, so nothing but the element itself can disappear.
  function soleWrapper(el) {
    var node = el;
    while (node.parentElement && node.parentElement !== document.body &&
        node.parentElement.childElementCount === 1) {
      node = node.parentElement;
    }
    return node;
  }

  function redirect(target) {
    // Backstop against a redirect loop if Instagram ever rewrites one of our target URLs.
    try {
      var last = Number(sessionStorage.getItem('cleangram-redirect')) || 0;
      if (Date.now() - last < 5000) return;
      sessionStorage.setItem('cleangram-redirect', String(Date.now()));
    } catch (e) {
      return;
    }
    location.replace(target);
  }

  function guardRoute() {
    var path = location.pathname;
    if (path === '/reels' || path.indexOf('/reels/') === 0) {
      redirect('/');
    } else if (path === '/explore' || path === '/explore/') {
      // Keep people search, drop the recommendation grid.
      redirect('/explore/search/');
    }
  }

  function checkPost(post) {
    // Only the header is read: a caption that happens to say "suggested for you" must not
    // hide someone's real post.
    var header = post.querySelector('header');
    if (header && SUGGESTED_IN_HEADER.test(textOf(header))) hide(post);
  }

  function checkControl(el) {
    if (el.matches(REELS_LINK)) {
      hide(soleWrapper(el));
      return;
    }
    var text = textOf(el);
    if (text.length < 20 && APP_PROMPT.test(text)) hide(soleWrapper(el));
  }

  // A "Suggested for you" heading outside a post heads a carousel of accounts to follow. Hide the
  // smallest block holding the heading and a Follow button - and only if that block contains no
  // post and no story, so it can never take the feed or the stories tray with it.
  function checkHeading(el) {
    if (el.childElementCount !== 0 || el.closest('article')) return;
    if (!SUGGESTED.test(textOf(el))) return;
    for (var node = el.parentElement; node && node !== document.body; node = node.parentElement) {
      var controls = node.querySelectorAll(CONTROL);
      var hasFollow = false;
      for (var i = 0; i < controls.length && !hasFollow; i++) {
        hasFollow = textOf(controls[i]) === 'follow';
      }
      if (!hasFollow) continue;
      if (!node.querySelector(PROTECTED)) hide(node);
      return;
    }
  }

  function inspect(root) {
    if (!root || root.nodeType !== 1 || !root.isConnected) return;

    var post = root.closest('article');
    if (post) checkPost(post);
    var posts = root.querySelectorAll('article');
    for (var i = 0; i < posts.length; i++) checkPost(posts[i]);

    var control = root.closest(CONTROL);
    if (control) checkControl(control);
    var controls = root.querySelectorAll(CONTROL);
    for (var j = 0; j < controls.length; j++) checkControl(controls[j]);

    if (root.matches('span, h2, h3, h4, div')) checkHeading(root);
    var headings = root.querySelectorAll('span, h2, h3, h4');
    for (var k = 0; k < headings.length; k++) checkHeading(headings[k]);
  }

  // Tell the app when there is something real to look at, so its loading screen hands straight
  // over to content rather than to Instagram's own splash and skeleton.
  function signalReady() {
    if (readySent || !document.querySelector('article, input[type="password"]')) return;
    readySent = true;
    var bridge = window.CleanGramBridge;
    if (bridge && typeof bridge.postMessage === 'function') bridge.postMessage('ready');
  }

  function pass() {
    addStyle();
    guardRoute();
    var roots = pending;
    pending = [];
    for (var i = 0; i < roots.length; i++) inspect(roots[i]);
    signalReady();
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      pass();
    });
  }

  addStyle();
  guardRoute();
  new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var added = records[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        var node = added[j];
        // Text arriving inside an existing element (React filling in a header) re-checks that
        // element, not just brand-new elements.
        pending.push(node.nodeType === 1 ? node : node.parentElement);
      }
    }
    schedule();
  }).observe(document, { childList: true, subtree: true });

  document.addEventListener('DOMContentLoaded', function () {
    pending.push(document.documentElement);
    pass();
  });
  window.addEventListener('popstate', schedule);
})();
