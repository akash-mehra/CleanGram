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
