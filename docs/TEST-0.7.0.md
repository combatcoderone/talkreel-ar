# Test v0.7.0, Music

## Package check, 2026-11-05
- Package com.talkreel.ar, versionCode 9, versionName 0.7.0, minSdk 26, targetSdk 34
- Signed v2 true, v3 true, v1 false, 1 signer, 89K
- dexdump: MusicCatalog, MusicEngine, MusicCatalog$Track present. SoundEngine absent.
- registerReceiver 3-arg fix from v0.6.1 still present in dex.

## Music download check, 2026-11-05, on the build machine, not the phone
- All 9 download URLs fetched with curl, each file confirmed as a real MP3 with `file`.
- 8 Pixabay tracks, 256 kbps, 2.2 to 5.0 MB each. Kevin MacLeod Monkeys Spinning Monkeys, 64 kbps, 3.3 MB.
- No emulator run was possible in this VM, same as v0.6.1, no KVM.

## Phone test, for Seyed
1. Clean install v0.7.0, open, camera shows, app stays open.
2. Scroll down to Music. Tap Play on "Feel Good Upbeat Motivational Pop". It should play, streaming, without download.
3. Tap Stop, then Download on the same track. It should say Downloaded, and the file should be in Music/TalkReelAR.
4. Tap Play again. It should now play the local file.
5. Tap Use on "Good Mood", then Record 5 seconds, then Stop. Music should play while recording, and stop on Stop.
6. Tap Overlay, then Music in the overlay. It should play or stop the picked track.
7. Report which step fails, with the exact message on screen.
