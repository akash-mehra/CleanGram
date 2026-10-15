# CleanGram

An Android WebView wrapper for Instagram that hides Reels-feed and Explore navigation and feed suggestions. Individual Reel posts shared in messages remain accessible.

## Media and layout

The Sound on/off button controls HTML video/audio and remembers your preference. Android device volume still controls playback loudness. Fullscreen video is supported; Back exits fullscreen before navigating. System bars and the keyboard are accounted for in the content size.

Filtering relies on Instagram DOM selectors and English suggestion labels. Test shared photos, videos, and Reels from DMs on a device after changes to Instagram. Native video fullscreen depends on the website exposing a fullscreen action.

## Build

Use JDK 21, Gradle 9.3.1, and an Android SDK matching app/build.gradle.kts:

```sh
gradle testDebugUnitTest
gradle assembleDebug
```

CI produces `CleanGram-Preview-APK` when release signing secrets are missing, so builds and UI testing remain available. Preview APKs are debug-signed and are not guaranteed to update your existing install or preserve its login. Once all four signing secrets are configured, CI instead produces `CleanGram-APK`, signed with the permanent release key.

Debug APKs from different machines may have different signing certificates. Use signed release APKs for regular installs and updates.

## Updates that preserve login

Android updates require the same application ID and signing certificate, with a non-decreasing version code. Keep the signing key permanently and back it up privately. Do not uninstall or clear app data when updating: doing so deletes Instagram cookies. Instagram can independently expire a login session.

Configure these repository Actions secrets:

- KEYSTORE_BASE64: base64 encoding of a permanent signing keystore.
- STORE_PASSWORD: keystore password.
- KEY_PASSWORD: signing key password.
- KEY_ALIAS: alias of the signing key.

Generate a key once if no existing key is available (keytool prompts for passwords):

```sh
keytool -genkeypair -keystore cleangram-release.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 cleangram-release.jks
```

Enter the base64 output directly as KEYSTORE_BASE64 in GitHub Settings > Secrets and variables > Actions. Never commit the keystore or passwords. The APK workflow restores the same key each run and uses its run number as the version code. Re-running a run keeps its version code; use a new workflow run for a new release. If moving/resetting this workflow, preserve increasing version codes.

If the installed APK's original signing key exists, reuse it (including its alias/passwords) to preserve the existing installation. Otherwise Android cannot replace it with a newly signed APK: one final uninstall/reinstall and login is needed for the transition. Subsequent release APKs can install over it and retain app data.

## Device checks

1. Open a photo, video and individual Reel shared in a DM. Check portrait and landscape layouts, keyboard open/close, and Back navigation.
2. Toggle sound during playback and on a newly loaded video; restart the app and verify the preference persists.
3. Enter/exit fullscreen if Instagram offers it.
4. Install a second release APK signed with the same key and a greater version code over the first. Confirm Instagram remains logged in.
5. Verify Reels-feed and Explore navigation remain hidden and direct Reels-feed navigation returns home.


## In-app updates

Settings > Check for updates checks the latest published GitHub release. Automatic checks are enabled by default and run when the app becomes active, at most once per 24 hours; disable them in Settings. A popup offers Download update or Later. This is a foreground popup, not a notification delivered while the app is closed.

After downloading, CleanGram verifies the exact size, GitHub SHA-256 digest, application ID, higher version code and matching signing certificate. Android asks for installation approval. On Android 8+, allow CleanGram to install apps when directed to Android settings, then return to confirm installation. No storage permission is required. Cancelled downloads are removed; completed downloads can be installed again through Settings. If the app is closed during a download, retry from Settings.

Signed builds on main automatically publish a GitHub release with a `v<versionCode>` tag and `cleangram-<versionCode>.apk` asset. Branch/PR builds never publish releases. The updater ignores drafts, prereleases, preview APKs, older versions, and assets missing a valid SHA-256 digest. Before the first release, it reports no newer published release. Releases keep the permanent signing key; changing the key will cause verification to reject the APK.

The first updater-enabled version must be installed manually over the existing signed app. Later published releases can be installed through the app. Android still requires the user's confirmation; updates never silently install or require uninstalling the app.

Updater device checks: manual checks with no release/equal/newer version, offline/rate-limit responses, Later/automatic checks off, download cancel, installer permission denied/granted, install cancel/retry, and an in-place upgrade retaining Instagram login.


## Native shell

CleanGram now reserves native layout bands above and below the WebView. Home, Messages and Profile drive Instagram's existing links/router, with ordinary HTTPS website navigation as a fallback. Instagram still handles login, posts, conversations, profiles, uploads and account state. Profile discovery uses only an explicitly labelled profile link; if Instagram changes or localizes that link, a username prompt is the safe fallback. No account is inferred from feed avatars and no unofficial Instagram API is used.

The dock floats visually inside its own reserved band, never over the WebView. It hides while the keyboard is visible. System/cutout and IME insets are applied once using the larger bottom inset, and dimensions adapt on rotation. Fullscreen video switches to a separate native layout with Close and Sound controls in a reserved strip above the media. Sound continues using the existing MainActivity preferences; cookies and WebView history/state are retained.

Light and dark resources follow device appearance. Navigation uses lightly translucent tints and borders over the shell background, without blurring the WebView or its text. Settings > Solid surfaces switches to opaque panels. Dialogs use optional Android 12+ cross-window blur only when the system allows it, with an opaque fallback when unavailable or disabled. Selection uses a short native animation and platform ripples; there are no looping decorative animations.

### Boundaries

- `ShellNavigation.kt` / `ShellLayout.kt`: native route and inset policies.
- `MainActivity.kt`: WebView lifecycle, native controls, sound, file chooser and fullscreen host.
- `SettingsPanel.kt`, `GlassSurface.kt`, `GlassDialogs.kt`, themed resources: native visual styling.
- `assets/instagram-shell.js`: native route notifications, link navigation and optional duplicate-nav suppression. It only suppresses a positively identified, compact bottom navigation landmark with Home and Inbox links, bounded height, and no content/form/media descendants. Unknown Instagram layouts keep their original controls.
- `assets/distraction-blocking.js`: discovery policy, kept separate from styling. Blocks `/reels` and `/explore` through full and client-side navigation, while preserving `/reel/<post>`, `/p/<post>`, DM attachments and followed posts. Suggestion removal stays limited to exact labels inside single articles.
- `assets/media-preferences.js`: saved global media mute preference.
- `AppUpdates.kt` / `UpdateRelease.kt`: existing digest, version, app ID and permanent-certificate validation and Android installer flow.

The route message channel is restricted to HTTPS Instagram origins and top-frame messages, exposes no account data or privileged operations, and has a history-callback fallback for older WebViews. Scripts are re-applied after document commits; document-start injection is feature-detected.

### Verification and previews

`node --test scripts/test-distraction-filter.cjs scripts/test-shell-navigation.cjs` exercises feed-preservation, discovery blocking, SPA navigation, authentication redirects, profile fallback, reinjection, targeted navigation suppression, and dynamic media preferences using deterministic DOM fixtures. These tests do not claim to reproduce Instagram's live DOM.

`gradle testDebugUnitTest lintDebug` runs native route/inset/version tests, layout separation and minimum touch-target checks, and renders the actual native layouts with Robolectric Native Graphics. The previews label the WebView area as a placeholder and contain no real Instagram account content. CI uploads previews and test/lint reports as `CleanGram-Android-Checks`, then builds a signed APK using the existing application ID and release secrets. Branch builds do not publish releases.

Real-device checks still required before release: Instagram login and persistence through in-place update; native tab navigation against the current English/localized site; followed feed and shared photo/video/Reel attachments; DM composer and photo picker; gesture/three-button insets; keyboard open/close and rotation; video fullscreen/Back; mute persistence; TalkBack and enlarged text; system dark mode and blur-disabled/solid surfaces; update check/download/cancel/permission/installation. Tests of native layout and script fixtures are distinct from these live-service checks.
