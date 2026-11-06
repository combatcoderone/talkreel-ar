# Why your phone does not trust TalkReel, and how to keep it

TalkReel v0.3 is not on Google Play yet. Any app installed from a file, not from Play Store, gets a warning from Play Protect. That is normal, it is not proof the app is harmful.

Why it warns:
- The app is new, and Play Protect has never seen this developer before.
- It asks for Camera, Mic, and Draw over other apps. Those are needed for recording and for the floating teleprompter, but Play Protect treats them as sensitive.
- It is signed with a self-made key, not a Play Store key.

What TalkReel does in v0.3:
- Camera and mic are only used while you record.
- Videos stay on your phone in Movies/TalkReelAR.
- Nothing is uploaded in v0.3. No account, no server upload.

How to install and keep it:
1. Uninstall all old TalkReel versions first. v0.3 uses a new release key, so it cannot update over the old debug key. You must uninstall first.
2. Download talk-reel-ar-v0.3.0.apk, open it from Files, allow Install unknown apps.
3. If Play Protect says "App not installed" or "Harmful app", tap Details, then Install anyway, or Keep. If it removes the app, open Play Store, tap your profile, Play Protect, Settings, turn off Scan apps, install TalkReel, then turn Scan back on.
4. Open TalkReel, allow Camera, Mic, and Notifications. Allow Draw over other apps only when you tap Start overlay.

The only way to fully remove this warning is to publish on Google Play. That is the v1.0 goal, after the features are stable.
