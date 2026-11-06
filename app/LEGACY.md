# app/ is legacy, not the shipped app

This folder is a Kotlin Compose prototype, v0.1 features only.

It does NOT have:

- The v0.2 background camera thread and foreground service fix
- The v0.2.1 install check, icon, clips list, or content URI share
- The v0.3 release keystore, ClipProvider, or face detection fix
- The v0.4 circle overlay, multilingual captions, or language detection
- The v0.5 camera release, transparent overlay, text colours, or direct app open
- The v0.6 professional rebuild, single recording owner, Pro tier, or ProFrameView
- The v0.6.1 Android 14 registerReceiver and startForeground fix
- The v0.7.0 Music section, with real downloadable songs

Its AndroidManifest lacks the foreground service, overlay permission,
ClipProvider, and POST_NOTIFICATIONS. Its version in build.gradle.kts
may say 0.7.0, but the code inside is v0.1.

The shipped source is `manual-apk/`, plain Java. Build that, with
`manual-apk/build.sh`. See the top-level README.md.

This folder is kept for history only, so nothing is lost. Do not build it
and expect the shipped app.
