# Music in TalkReel AR, v0.7.0

## Why this changed

v0.1 to v0.6 had a Sound button that played synthesized melodies made in code.
Seyed said that music was awful. He was right. A beep melody is not music a
creator wants under a review.

v0.7.0 replaces it with real songs, from open sources, free to download.

## How it works in the app

The Music section is on its own, at the bottom of the main screen, under the
script editor. Each track has three buttons.

- Play: test the track first. If it is already downloaded, it plays the local
  file. If it is not downloaded yet, it streams the same download URL, so you
  can hear it before you spend data on a download. Tap Stop to stop it.
- Download: saves the MP3 in TalkReel's app-specific external Music/TalkReelAR
  directory (with internal app storage as a fallback). This avoids direct
  writes to public shared storage under Android scoped-storage rules. Nothing
  is bundled in the APK.
- Use: picks this track for the next clip. In the Media3-enabled Gradle build,
  the downloaded track is mixed under microphone audio during finished-video
  export. Studio does not play the track through the speaker while recording.

In the overlay over Instagram or YouTube, the Music button plays or stops
the track picked in the Music section.

The Media3-enabled Gradle build mixes the selected local track into a finished
MP4 after recording. The standalone manual APK build intentionally excludes
the Media3 dependency and therefore does not provide digital music mixing.
The original recording is retained if the finished export fails. The selected
track must be downloaded before Studio recording can start with Music enabled.

## The tracks, all verified on 2026-11-05

Every download URL below was checked with curl on 2026-11-05. Each file
downloaded and `file` confirmed it is a real MP3, 256 kbps for Pixabay,
64 kbps for the Wikimedia copy of the Kevin MacLeod track.

Viral means here: upbeat, happy, motivational, and funny tracks that are
already used a lot in Reels, Shorts, and TikTok, short enough for a 30 to
60 second clip, with a clear hook in the first seconds.

### 1. Feel Good Upbeat Motivational Pop, Music_for_Creators, 2:34
Upbeat electronic pop. For Reels, vlog, fitness, travel.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/upbeat-feel-good-upbeat-motivational-pop-312622/
Download: https://cdn.pixabay.com/download/audio/2025/03/12/audio_b466bacd50.mp3?filename=music_for_creators-feel-good-upbeat-motivational-pop-312622.mp3

### 2. Happy Upbeat Pop, LNPlusMusic, 2:04
Happy, catchy, animated. For review, unboxing, kids, product.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/upbeat-happy-upbeat-pop-background-music-intro-theme-286239/
Download: https://cdn.pixabay.com/download/audio/2025/01/15/audio_d0cdb0de93.mp3?filename=lnplusmusic-catchy-animated-toddler-music-286239.mp3

### 3. Good Mood, SoulProdMusic, 1:37
Happy, upbeat, positive. For review, good news, lifestyle.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/pop-good-mood-happy-upbeat-background-music-123759/
Download: https://cdn.pixabay.com/download/audio/2022/10/23/audio_d6bce2ca97.mp3?filename=soulprodmusic-good-mood-happy-upbeat-background-music-123759.mp3

### 4. Energetic Upbeat Background Music, HitsLab, 1:34
Energetic, confident, promo. For a Shorts hook, promo, product launch.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/upbeat-energetic-upbeat-background-music-333747/
Download: https://cdn.pixabay.com/download/audio/2025/04/28/audio_8b53d9b3fe.mp3?filename=hitslab-upbeat-upbeat-music-333747.mp3

### 5. Upbeat Promo Music, Maksym Malko, 1:09
Upbeat, stomp drum, dynamic. For intro, promo, unboxing.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/upbeat-upbeat-promo-music-dynamic-energetic-stomp-drum-background-intro-284461/
Download: https://cdn.pixabay.com/download/audio/2025/01/13/audio_9daa8083c5.mp3?filename=maksymmalko-upbeat-promo-music-dynamic-energetic-stomp-drum-background-intro-284461.mp3

### 6. Upbeat Happy Indie Pop, Vivaleum, 2:38
Indie pop, happy, light. For travel, lifestyle, daily vlog.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/indie-pop-upbeat-happy-indie-pop-186815/
Download: https://cdn.pixabay.com/download/audio/2024/01/18/audio_164e3819f5.mp3?filename=vivaleum-upbeat-happy-indie-pop-186815.mp3

### 7. Inspirational Motivational Music, LNPlusMusic, 1:57
Inspirational, motivational. For business, success, story, talk.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/upbeat-inspirational-motivational-music-311778/
Download: https://cdn.pixabay.com/download/audio/2025/03/10/audio_9eaa9f574c.mp3?filename=lnplusmusic-inspirational-motivational-music-311778.mp3

### 8. Motivational Uplifting Outdoors, DayNigthMorning, 2:27
Uplifting, outdoors, adventure. For outdoors, travel, motivational.
License: Pixabay License, free, no attribution.
Page: https://pixabay.com/music/upbeat-outdoors-no-copyright-music-motivational-uplifting-388463/
Download: https://cdn.pixabay.com/download/audio/2025/08/12/audio_aceed0e87f.mp3?filename=daynigthmorning-outdoors-no-copyright-music-motivational-uplifting-388463.mp3

### 9. Monkeys Spinning Monkeys, Kevin MacLeod, 2:05
Funny, happy, viral classic. Used in countless videos, memes, and pet clips.
For funny, pets, memes, light review.
License: CC BY 4.0, credit required. Put this in the video description:
"Monkeys Spinning Monkeys by Kevin MacLeod, incompetech.com, CC BY 4.0"
Page: https://commons.wikimedia.org/wiki/File:Monkeys_Spinning_Monkeys_by_Kevin_MacLeod.ogg
Download: https://upload.wikimedia.org/wikipedia/commons/transcoded/f/fe/Monkeys_Spinning_Monkeys_by_Kevin_MacLeod.ogg/Monkeys_Spinning_Monkeys_by_Kevin_MacLeod.ogg.mp3

## Sources, if you want more music

- Pixabay Music, https://pixabay.com/music/ : free, commercial use, no
  attribution required, no account to download. This is where tracks 1 to 8
  come from. Search by mood, genre, duration, and tempo.
- Free Music Archive, https://freemusicarchive.org/ : Creative Commons
  library, check the license on each track. CC BY needs credit, CC BY-NC is
  non-commercial only, CC0 needs nothing.
- Incompetech, https://incompetech.com/ : Kevin MacLeod, over 2,000 tracks,
  free under CC BY 4.0 with credit, or buy a license to skip the credit.
- Mixkit, https://mixkit.co/free-stock-music/ : free, no attribution, no
  account. License allows online video, podcasts, and ads, not TV, radio,
  games, or CDs and DVDs.
- YouTube Audio Library, inside YouTube Studio: free, cleared for YouTube,
  most tracks need no attribution, a few do. Safest for YouTube only.

## Rules, so a track does not get a video muted

- Pixabay License allows commercial use with no attribution, but do not
  re-upload a track on its own, do not sell it as music, and do not claim
  you made it. Some Pixabay tracks are registered in Content ID, so keep
  the track page link with the clip if YouTube asks.
- CC BY tracks must be credited in the video description, every time.
- Instagram and TikTok can still mute a video even for a licensed track,
  because their own detection is separate. If that happens, use Instagram's
  own music library inside the Reels editor for that post, or the track
  from Meta Sound Collection.
- Never download a famous commercial song from a random site and put it in
  a video. That is not what these sources are for.

## How to add a new track

Add one entry to MusicCatalog.TRACKS, with title, artist, mood, use, duration,
license, credit if any, page URL, download URL, and file name. Check the
download URL with curl first, and confirm `file` says MP3. Update this file
with the same track, in the same format. Do not add a track you have not
downloaded and checked.
