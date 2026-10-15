(function () {
    if (!document.documentElement) return;
    // Presentation and website routing only; discovery policy is a separate asset.
    if (window.__cleanGramShell) { window.__cleanGramShell.refresh(); return; }
    const excluded = 'article, [role="dialog"], form';
    const activityPaths = ['/accounts/activity/', '/notifications/'];
    let siteControls = false, topClearance = 64, bottomClearance = 88;
    let lastReportedPath = null, pendingNavigation = 0;
    const hidden = new Map(), shifted = new Map();
    const residualNavigation = new Set();
    function pathOf(link) {
        if (!link || typeof link.href !== "string" || !link.href) return null;
        try {
            const url = new URL(link.href, location.href);
            return url.origin === location.origin ? url.pathname.replace(/\/?$/, '/') : null;
        } catch (_) { return null; }
    }
    function profileLink() {
        return Array.from(document.querySelectorAll('a[href]')).find(function (link) {
            const path = pathOf(link);
            if (!path || !/^\/[A-Za-z0-9._]{1,30}\/$/.test(path) ||
                /^\/(accounts|notifications|direct|p|reel|reels|explore|stories|about|legal|challenge|oauth|emails|web)\/$/i.test(path) || link.closest(excluded)) return false;
            const label = (link.getAttribute('aria-label') || '').trim().toLowerCase();
            return label === 'profile' || label === 'your profile' ||
                !!link.querySelector('[aria-label="Profile" i], [aria-label="Your profile" i]') ||
                ((link.textContent || '').trim().toLowerCase() === 'profile' && !link.closest('main'));
        });
    }
    function notificationControl() {
        return Array.from(document.querySelectorAll('a[href], button, [role="button"]')).find(function (control) {
            if (control.closest(excluded)) return false;
            return activityPaths.includes(pathOf(control)) ||
                /^(notifications|activity)$/i.test(control.getAttribute('aria-label') || '') ||
                !!control.querySelector('[aria-label="Notifications" i], [aria-label="Activity" i]');
        });
    }
    function reportRoute() {
        const bridge = window.CleanGramShell;
        if (bridge && bridge.postMessage && lastReportedPath !== location.pathname) {
            lastReportedPath = location.pathname;
            bridge.postMessage(JSON.stringify({path: location.pathname}));
        }
    }
    function hide(element) {
        if (!hidden.has(element)) hidden.set(element, [element.style.getPropertyValue('display'), element.style.getPropertyPriority('display')]);
        element.style.setProperty('display', 'none', 'important');
    }
    function restoreHidden() {
        hidden.forEach(function (original, element) {
            if (original[0]) element.style.setProperty('display', original[0], original[1]);
            else element.style.removeProperty('display');
        });
        hidden.clear();
    }
    function isDuplicate(control, profile, notification) {
        const path = pathOf(control);
        return path === '/' || path === '/direct/inbox/' || control === profile || control === notification;
    }
    function compactChrome(element) {
        if (!element || element.closest(excluded) || element.querySelector('article, main, form, video, input, textarea, [role="dialog"]')) return false;
        const box = element.getBoundingClientRect();
        return box.height > 0 && box.height <= 104 &&
            (box.top >= window.innerHeight - 140 || box.bottom <= 120);
    }
    function integrateNavigation() {
        residualNavigation.clear();
        const profile = profileLink(), notification = notificationControl();
        const controls = Array.from(document.querySelectorAll('a[href], button, [role="button"]'));
        // Locate dedicated compact chrome even when Instagram uses generic divs.
        // Only climb three levels; never hide an unbounded root/container.
        const candidates = new Set(document.querySelectorAll('nav, [role="navigation"]'));
        controls.filter(c => pathOf(c) === '/' && !c.closest(excluded)).forEach(function (home) {
            let parent = home.parentElement;
            for (let depth = 0; parent && depth < 3; depth++, parent = parent.parentElement) {
                if (compactChrome(parent) && parent.querySelector('a[href="/direct/inbox/"]')) candidates.add(parent);
            }
        });
        candidates.forEach(function (nav) {
            if (!compactChrome(nav) || !nav.querySelector('a[href="/"]') || !nav.querySelector('a[href="/direct/inbox/"]')) return;
            const actions = Array.from(nav.querySelectorAll('a[href], button, [role="button"]'));
            // A Create, upload, search, menu or unknown action keeps its container.
            const remaining = actions.filter(c => !isDuplicate(c, profile, notification) &&
                !/^\/(reels|explore)\//.test(pathOf(c) || ''));
            if (actions.length && !remaining.length) hide(nav);
            else {
                actions.filter(c => isDuplicate(c, profile, notification)).forEach(hide);
                residualNavigation.add(nav);
            }
        });
        // Also replace independent explicitly labelled shortcuts in small chrome.
        [profile, notification].filter(Boolean).forEach(function (control) {
            if (compactChrome(control)) hide(control);
        });
    }
    function clearance() {
        let style = document.getElementById('cleangram-shell-clearance');
        if (!style) {
            style = document.createElement('style'); style.id = 'cleangram-shell-clearance';
            document.documentElement.appendChild(style);
        }
        // Scoped, additive clearance. No ancestor is hidden or resized to crop content.
        const css = 'html[data-cleangram-shell] {scroll-padding-top:' + topClearance + 'px;scroll-padding-bottom:' + bottomClearance + 'px}' +
            'body[data-cleangram-shell] {padding-top:' + topClearance + 'px!important;padding-bottom:' + bottomClearance + 'px!important;box-sizing:border-box}' +
            'main[data-cleangram-scroll] {scroll-padding-top:' + topClearance + 'px;scroll-padding-bottom:' + bottomClearance + 'px;padding-bottom:' + bottomClearance + 'px!important;box-sizing:border-box}';
        if (style.textContent !== css) style.textContent = css;
        document.documentElement.setAttribute('data-cleangram-shell', '');
        if (document.body) document.body.setAttribute('data-cleangram-shell', '');
        document.querySelectorAll('main').forEach(function (main) {
            const computed = window.getComputedStyle(main);
            if (/(auto|scroll)/.test(computed.overflowY)) main.setAttribute('data-cleangram-scroll', '');
        });
        // Keep fixed site headers (including Create) below the compact native toolbar.
        // Remember original offsets so keyboard/rotation/preferences can restore them.
        shifted.forEach(function (original, element) {
            if (original.value) element.style.setProperty(original.property, original.value, original.priority);
            else element.style.removeProperty(original.property);
        });
        shifted.clear();
        document.querySelectorAll('header, [role="banner"]').forEach(function (header) {
            if (!compactChrome(header)) return;
            const computed = window.getComputedStyle(header);
            if (!['fixed', 'sticky'].includes(computed.position)) return;
            const offset = parseFloat(computed.top);
            if (!Number.isFinite(offset) || offset > topClearance) return;
            shifted.set(header, {property:'top', value:header.style.getPropertyValue('top'), priority:header.style.getPropertyPriority('top')});
            header.style.setProperty('top', (offset + topClearance) + 'px');
        });
        residualNavigation.forEach(function (nav) {
            const computed = window.getComputedStyle(nav);
            const offset = parseFloat(computed.bottom);
            if (computed.position !== 'fixed' || !Number.isFinite(offset)) return;
            shifted.set(nav, {property:'bottom', value:nav.style.getPropertyValue('bottom'), priority:nav.style.getPropertyPriority('bottom')});
            nav.style.setProperty('bottom', (offset + bottomClearance) + 'px');
        });
    }
    function refresh() {
        // Restore first: React may reuse a node for a different action on navigation.
        restoreHidden();
        residualNavigation.clear();
        if (!siteControls) integrateNavigation();
        clearance(); reportRoute();
    }
    function setClearance(top, bottom, showSiteControls) {
        topClearance = Math.max(0, Math.min(120, Number(top) || 0));
        bottomClearance = Math.max(0, Math.min(140, Number(bottom) || 0));
        siteControls = !!showSiteControls; refresh();
    }
    function navigate(destination) {
        const profile = destination === 'profile' ? profileLink() : null;
        const notification = destination === 'notifications' ? notificationControl() : null;
        const target = destination === 'home' ? '/' : destination === 'messages' ? '/direct/inbox/' :
            destination === 'notifications' ? (pathOf(notification) || '/accounts/activity/') : profile ? pathOf(profile) : null;
        if (!target) return false;
        const link = notification || profile || Array.from(document.querySelectorAll('a[href]')).find(item => pathOf(item) === target);
        const ticket = ++pendingNavigation;
        if (location.pathname === target && !link) { reportRoute(); return true; }
        if (link) link.click();
        // Button-backed notifications may open an Instagram panel without a URL change.
        if (notification && !pathOf(notification)) return true;
        const startingPath = location.pathname;
        setTimeout(function () {
            if (ticket !== pendingNavigation) return;
            if (location.pathname !== target && location.pathname === startingPath &&
                !/^\/(accounts\/(login|signup)|challenge)\//.test(location.pathname)) location.assign(target);
            reportRoute();
        }, 600);
        return true;
    }
    window.__cleanGramShell = {refresh: refresh, navigate: navigate, setClearance: setClearance};
    ['pushState', 'replaceState'].forEach(function (method) {
        const original = history[method];
        history[method] = function () { const result = original.apply(this, arguments); refresh(); return result; };
    });
    window.addEventListener('popstate', function () { pendingNavigation++; refresh(); });
    window.addEventListener('resize', refresh);
    let pending = false;
    const observer = new MutationObserver(function () {
        if (pending) return;
        pending = true;
        requestAnimationFrame(function () { pending = false; refresh(); });
    });
    observer.observe(document.documentElement, {childList: true, subtree: true, attributes: true, attributeFilter: ['href', 'aria-label']});
    refresh();
})();
