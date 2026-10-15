(function () {
    if (!document.documentElement) return;
    // Visual integration only. Discovery policy lives in distraction-blocking.js.
    if (window.__cleanGramShell) { window.__cleanGramShell.refresh(); return; }
    const validProfile = function (path) {
        return /^\/[A-Za-z0-9._]{1,30}\/$/.test(path) &&
            !/^\/(accounts|direct|p|reel|reels|explore|stories|about|legal|challenge|oauth|emails|web)\/$/i.test(path);
    };
    function profileLink() {
        const links = Array.from(document.querySelectorAll('a[href]'));
        return links.find(function (link) {
            let url;
            try { url = new URL(link.href, location.href); } catch (_) { return false; }
            if (url.origin !== location.origin || !validProfile(url.pathname)) return false;
            // Explicit labels only. Never infer the signed-in account from a feed avatar.
            const label = (link.getAttribute('aria-label') || link.textContent || '').trim().toLowerCase();
            return label === 'profile' || label === 'your profile' ||
                !!link.querySelector('[aria-label="Profile" i], [aria-label="Your profile" i]');
        });
    }
    function reportRoute() {
        const bridge = window.CleanGramShell;
        if (bridge && bridge.postMessage) bridge.postMessage(JSON.stringify({path: location.pathname}));
    }
    function refresh() {
        // Hide only a small, dedicated bottom navigation landmark if positively
        // identified. Unknown layouts keep Instagram's controls as a safe fallback.
        document.querySelectorAll('nav, [role="navigation"]').forEach(function (nav) {
            if (nav.hasAttribute('data-cleangram-navigation')) return;
            const box = nav.getBoundingClientRect();
            if (box.height <= 0 || box.height > 96 || box.top < window.innerHeight - 128) return;
            if (!nav.querySelector('a[href="/"]') || !nav.querySelector('a[href="/direct/inbox/"]')) return;
            if (nav.querySelector('article, main, form, video, input, [role="dialog"]')) return;
            nav.setAttribute('data-cleangram-navigation', '');
            nav.style.setProperty('display', 'none', 'important');
        });
        reportRoute();
    }
    function navigate(destination) {
        const profile = destination === 'profile' ? profileLink() : null;
        const target = destination === 'home' ? '/' : destination === 'messages' ? '/direct/inbox/' : profile ? new URL(profile.href, location.href).pathname : null;
        if (!target) return false;
        const link = profile || Array.from(document.querySelectorAll('a[href]')).find(function (item) {
            try { const url = new URL(item.href, location.href); return url.origin === location.origin && url.pathname === target; }
            catch (_) { return false; }
        });
        if (location.pathname === target) { reportRoute(); return true; }
        if (link) link.click();
        // Prefer Instagram's own router, with a regular website navigation fallback.
        setTimeout(function () {
            if (location.pathname !== target && !location.pathname.startsWith('/accounts/') && !location.pathname.startsWith('/challenge/')) location.assign(target);
            reportRoute();
        }, 600);
        return true;
    }
    window.__cleanGramShell = {refresh: refresh, navigate: navigate};
    ['pushState', 'replaceState'].forEach(function (method) {
        const original = history[method];
        history[method] = function () { const result = original.apply(this, arguments); refresh(); return result; };
    });
    window.addEventListener('popstate', refresh);
    let pending = false;
    const observer = new MutationObserver(function () {
        if (pending) return;
        pending = true;
        requestAnimationFrame(function () { pending = false; refresh(); });
    });
    observer.observe(document.documentElement, {childList: true, subtree: true, attributes: true, attributeFilter: ['href']});
    refresh();
})();
