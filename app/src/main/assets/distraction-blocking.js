(function() {
    if (!document.documentElement) return;
                const STYLE_ID = 'cleangram-distraction-free-css';
                const SCRIPT_INIT_KEY = '__cleangram_observer_active__';

                const customCss = `
                    /* Target discovery links/icons, never generic feed containers. */
                    a[href="/reels"], a[href^="/reels/"],
                    a[href="/explore"], a[href^="/explore/"],
                    a[href^="https://www.instagram.com/reels/"],
                    a[href^="https://www.instagram.com/explore/"],
                    svg[aria-label="Reels" i],
                    svg[aria-label="Explore" i],
                    button[aria-label="Reels" i],
                    button[aria-label="Explore" i] {
                        display: none !important;
                    }
                `;

                function injectCSS() {
                    if (!document.getElementById(STYLE_ID)) {
                        const style = document.createElement('style');
                        style.id = STYLE_ID;
                        style.type = 'text/css';
                        style.appendChild(document.createTextNode(customCss));
                        const target = document.head || document.documentElement;
                        if (target) {
                            target.appendChild(style);
                        }
                    }
                }

                function removeDistractionNodes() {
                    if (document.documentElement) {
                        document.documentElement.toggleAttribute('data-cleangram-home', location.pathname === '/');
                    }
                    enforceRoute();
                    // Hide parent containers for Reels & Explore navigation items
                    const targetSelectors = [
                        'a[href="/reels"], a[href^="/reels/"]',
                        'a[href="/explore"], a[href^="/explore/"]',
                        'a[href^="https://www.instagram.com/reels/"]',
                        'a[href^="https://www.instagram.com/explore/"]',
                        'svg[aria-label="Reels" i]',
                        'svg[aria-label="Explore" i]'
                    ];

                    document.querySelectorAll(targetSelectors.join(',')).forEach(function(el) {
                        const navParent = el.closest('a, button, [role="link"], [role="button"], [role="tab"], [role="menuitem"]');
                        if (navParent && navParent !== document.body && navParent !== document.documentElement) {
                            navParent.style.setProperty('display', 'none', 'important');
                            navParent.style.setProperty('visibility', 'hidden', 'important');
                        } else {
                            el.style.setProperty('display', 'none', 'important');
                            el.style.setProperty('visibility', 'hidden', 'important');
                        }
                    });

                    // Hide "Suggested for you" algorithmic recommendation containers in main feed
                    const suggestionKeywords = [
                        'suggested for you',
                        'suggestions for you',
                        'suggested posts',
                        'suggested accounts',
                        'suggested reels'
                    ];

                    if (location.pathname !== '/') return;
                    // A Reel permalink can belong to a followed-account post.
                    // Keep these posts; block the discovery feed via its route instead.
                    const textNodes = document.querySelectorAll('article span, article p, article h2, article h3, article h4');
                    textNodes.forEach(function(node) {
                        if (node.children.length === 0 && node.textContent) {
                            const trimmedText = node.textContent.trim().toLowerCase();
                            if (suggestionKeywords.includes(trimmedText)) {
                                // Only hide a single semantic post, never section,
                                // presentation wrappers, or the heading's parent.
                                const post = node.closest('article');
                                if (post && !post.querySelector('article') &&
                                    !post.querySelector('nav, main, [role="main"], [role="navigation"]')) {
                                    post.style.setProperty('display', 'none', 'important');
                                }

                            }
                        }
                    });
                }


                function blockedRoute(url) {
                    try {
                        const parsed = new URL(url, location.href);
                        if (parsed.origin !== location.origin) return false;
                        return /^\/(reels|explore)(\/|$)/i.test(parsed.pathname);
                    } catch (_) { return false; }
                }

                function enforceRoute() {
                    if (blockedRoute(location.href)) location.replace('/');
                }

                // Instagram changes routes without loading a new document.
                if (!window.__cleangramRouteGuard) {
                    window.__cleangramRouteGuard = true;
                    ['pushState', 'replaceState'].forEach(function(method) {
                        const original = history[method];
                        history[method] = function(state, title, url) {
                            if (url != null && blockedRoute(url)) {
                                enforceRoute();
                                return;
                            }
                            const result = original.apply(this, arguments);
                            removeDistractionNodes();
                            return result;
                        };
                    });
                    document.addEventListener('click', function(event) {
                        const link = event.target.closest && event.target.closest('a[href]');
                        if (link && blockedRoute(link.href)) {
                            event.preventDefault();
                            event.stopImmediatePropagation();
                        }
                    }, true);
                    window.addEventListener('popstate', removeDistractionNodes);
                    window.addEventListener('hashchange', removeDistractionNodes);
                }

                // Initial injection
                injectCSS();
                removeDistractionNodes();

                // Setup MutationObserver to continuously hide dynamically rendered items as user scrolls
                if (!window[SCRIPT_INIT_KEY]) {
                    window[SCRIPT_INIT_KEY] = true;
                    let pending = false;
                    const observer = new MutationObserver(function() {
                        if (pending) return;
                        pending = true;
                        requestAnimationFrame(function() {
                            pending = false;
                            injectCSS();
                            removeDistractionNodes();
                        });
                    });

                    const target = document.documentElement || document.body;
                    if (target) {
                        observer.observe(target, {
                            childList: true,
                            subtree: true
                        });
                    }
                }
            })();
