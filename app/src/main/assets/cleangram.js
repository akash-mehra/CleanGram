// CleanGram page filter. Registered to run at document start on every Instagram page, so it is
// in place before Instagram's own scripts and survives in-app (history.pushState) navigation.
//
// Design rules, learned the hard way:
//  - Never hide a container. Hiding the nearest <section>/<div> took the whole feed and the
//    stories tray with it. Only links, buttons and single posts are ever hidden.
//  - Never scan the whole document per mutation. Work is batched into one pass per 250 ms
//    and each pass only looks at elements it has not already checked.
(function () {
  'use strict';
  if (window.__cleangram) return;
  window.__cleangram = true;

  var FEED = '/?variant=following';
  var SEARCH = '/explore/search/';
  var STYLE_ID = 'cleangram-style';

  // The Reels tab. "/reels/" is plural; a followed account's reel post links to "/reel/<id>/"
  // and must stay visible, as must usernames that merely start with "reels".
  var CSS = 'a[href="/reels/"],a[href^="/reels/"],a[href^="/reels?"]{display:none!important}';

  var APP_PROMPT = /^(open app|open in app|use the app|get the app|open instagram|use app)$/;
  var SUGGESTED = /suggested (for you|posts|reels)/;

  var seen = typeof WeakSet === 'function' ? new WeakSet() : null;

  function addStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = CSS;
    root.appendChild(style);
  }

  function signedIn() {
    // ds_user_id is Instagram's script-readable signed-in marker. A password field means the
    // login form is up. Only redirect the home route when both say "signed in", so the login
    // screen can never be yanked away mid-typing.
    return /(?:^|;\s*)ds_user_id=/.test(document.cookie) &&
      !document.querySelector('input[type="password"]');
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
      redirect(FEED);
    } else if (path === '/explore' || path === '/explore/') {
      // Keep people search, drop the recommendation grid.
      redirect(SEARCH);
    } else if (path === '/' && location.search.indexOf('variant=following') === -1 &&
        document.readyState !== 'loading' && signedIn()) {
      // The logo and home tab lead to the algorithmic "For you" feed. Wait for the page to
      // parse first: at document start a login form's password field doesn't exist yet.
      redirect(FEED);
    }
  }

  function unseen(el) {
    if (!seen) return true;
    if (seen.has(el)) return false;
    seen.add(el);
    return true;
  }

  function hideSuggestedPosts() {
    var posts = document.querySelectorAll('article');
    for (var i = 0; i < posts.length; i++) {
      var post = posts[i];
      if (seen && seen.has(post)) continue;
      var header = post.querySelector('header');
      if (!header) continue; // not rendered yet; look again next pass
      unseen(post);
      // Only the header is read: a caption that happens to say "suggested for you" must not
      // hide someone's real post.
      if (SUGGESTED.test((header.textContent || '').toLowerCase())) {
        post.style.setProperty('display', 'none', 'important');
      }
    }
  }

  function hideAppPrompts() {
    var controls = document.querySelectorAll('a, button, [role="button"]');
    for (var i = 0; i < controls.length; i++) {
      var el = controls[i];
      if (!unseen(el)) continue;
      var text = (el.textContent || '').trim().toLowerCase();
      if (text.length < 20 && APP_PROMPT.test(text)) {
        el.style.setProperty('display', 'none', 'important');
      }
    }
  }

  function pass() {
    addStyle();
    guardRoute();
    hideSuggestedPosts();
    hideAppPrompts();
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      pass();
    }, 250);
  }

  addStyle();
  guardRoute();
  new MutationObserver(schedule).observe(document, { childList: true, subtree: true });
  document.addEventListener('DOMContentLoaded', pass);
  window.addEventListener('popstate', schedule);
})();
