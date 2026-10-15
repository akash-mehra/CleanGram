(function () {
    if (!document.documentElement) return;
    function apply() {
        document.querySelectorAll('video, audio').forEach(function (media) {
            if (media.muted !== window.__cleangramMuted) media.muted = window.__cleangramMuted;
        });
    }
    apply();
    if (!window.__cleangramSoundInstalled) {
        window.__cleangramSoundInstalled = true;
        document.addEventListener('play', apply, true);
        document.addEventListener('volumechange', apply, true);
        new MutationObserver(apply).observe(document.documentElement, {childList: true, subtree: true});
    }
})();
