package com.talkreel.ar;

// Music catalog for TalkReel, v0.7.0.
// The old SoundEngine played synthesized beeps, the user said that music was awful.
// These are real songs, from open sources, free to download and use in videos.
// Every download URL below was checked on 2026-11-05: curl downloaded the file
// and `file` confirmed it is a real MP3, 2 to 5 MB each, 256 kbps for Pixabay.
// Nothing is bundled in the APK. The app downloads a track only when the user
// taps Download, and saves it locally in Music/TalkReelAR. Play works before
// download too, by streaming the same URL, so the user can test a track first.
// Viral use means: upbeat, happy, motivational, and funny tracks that are
// already used a lot in Reels, Shorts, and TikTok, short enough for a 30 to
// 60 second clip, with a clear hook in the first seconds.
// Licenses, read the track page before a big commercial project:
// - Pixabay License: free for commercial and non-commercial use, no attribution
//   required. Do not sell or re-upload the track on its own, and do not claim
//   you made it. Some Pixabay tracks are in Content ID, keep the track page.
// - CC BY 4.0, Kevin MacLeod only: free, even for monetized videos, but you
//   must credit him in the video description. See the credit line in the track.
public class MusicCatalog {
    public static class Track {
        public final String title;
        public final String artist;
        public final String mood;
        public final String useFor;
        public final String duration;
        public final String license;
        public final String credit;
        public final String pageUrl;
        public final String downloadUrl;
        public final String fileName;
        public Track(String title, String artist, String mood, String useFor, String duration, String license, String credit, String pageUrl, String downloadUrl, String fileName) {
            this.title = title; this.artist = artist; this.mood = mood; this.useFor = useFor;
            this.duration = duration; this.license = license; this.credit = credit;
            this.pageUrl = pageUrl; this.downloadUrl = downloadUrl; this.fileName = fileName;
        }
        public String label() { return title + " - " + artist + " (" + duration + ")"; }
    }

    public static final Track[] TRACKS = {
        new Track("Feel Good Upbeat Motivational Pop", "Music_for_Creators", "Upbeat, electronic pop", "Reels, vlog, fitness, travel", "2:34", "Pixabay License, no attribution", "", "https://pixabay.com/music/upbeat-feel-good-upbeat-motivational-pop-312622/", "https://cdn.pixabay.com/download/audio/2025/03/12/audio_b466bacd50.mp3?filename=music_for_creators-feel-good-upbeat-motivational-pop-312622.mp3", "feel-good-upbeat.mp3"),
        new Track("Happy Upbeat Pop", "LNPlusMusic", "Happy, catchy, animated", "Review, unboxing, kids, product", "2:04", "Pixabay License, no attribution", "", "https://pixabay.com/music/upbeat-happy-upbeat-pop-background-music-intro-theme-286239/", "https://cdn.pixabay.com/download/audio/2025/01/15/audio_d0cdb0de93.mp3?filename=lnplusmusic-catchy-animated-toddler-music-286239.mp3", "happy-upbeat-pop.mp3"),
        new Track("Good Mood", "SoulProdMusic", "Happy, upbeat, positive", "Review, good news, lifestyle", "1:37", "Pixabay License, no attribution", "", "https://pixabay.com/music/pop-good-mood-happy-upbeat-background-music-123759/", "https://cdn.pixabay.com/download/audio/2022/10/23/audio_d6bce2ca97.mp3?filename=soulprodmusic-good-mood-happy-upbeat-background-music-123759.mp3", "good-mood.mp3"),
        new Track("Energetic Upbeat Background Music", "HitsLab", "Energetic, confident, promo", "Shorts hook, promo, product launch", "1:34", "Pixabay License, no attribution", "", "https://pixabay.com/music/upbeat-energetic-upbeat-background-music-333747/", "https://cdn.pixabay.com/download/audio/2025/04/28/audio_8b53d9b3fe.mp3?filename=hitslab-upbeat-upbeat-music-333747.mp3", "energetic-upbeat.mp3"),
        new Track("Upbeat Promo Music", "Maksym Malko", "Upbeat, stomp drum, dynamic", "Intro, promo, unboxing", "1:09", "Pixabay License, no attribution", "", "https://pixabay.com/music/upbeat-upbeat-promo-music-dynamic-energetic-stomp-drum-background-intro-284461/", "https://cdn.pixabay.com/download/audio/2025/01/13/audio_9daa8083c5.mp3?filename=maksymmalko-upbeat-promo-music-dynamic-energetic-stomp-drum-background-intro-284461.mp3", "upbeat-promo.mp3"),
        new Track("Upbeat Happy Indie Pop", "Vivaleum", "Indie pop, happy, light", "Travel, lifestyle, daily vlog", "2:38", "Pixabay License, no attribution", "", "https://pixabay.com/music/indie-pop-upbeat-happy-indie-pop-186815/", "https://cdn.pixabay.com/download/audio/2024/01/18/audio_164e3819f5.mp3?filename=vivaleum-upbeat-happy-indie-pop-186815.mp3", "upbeat-happy-indie-pop.mp3"),
        new Track("Inspirational Motivational Music", "LNPlusMusic", "Inspirational, motivational", "Business, success, story, talk", "1:57", "Pixabay License, no attribution", "", "https://pixabay.com/music/upbeat-inspirational-motivational-music-311778/", "https://cdn.pixabay.com/download/audio/2025/03/10/audio_9eaa9f574c.mp3?filename=lnplusmusic-inspirational-motivational-music-311778.mp3", "inspirational-motivational.mp3"),
        new Track("Motivational Uplifting Outdoors", "DayNigthMorning", "Uplifting, outdoors, adventure", "Outdoors, travel, motivational", "2:27", "Pixabay License, no attribution", "", "https://pixabay.com/music/upbeat-outdoors-no-copyright-music-motivational-uplifting-388463/", "https://cdn.pixabay.com/download/audio/2025/08/12/audio_aceed0e87f.mp3?filename=daynigthmorning-outdoors-no-copyright-music-motivational-uplifting-388463.mp3", "motivational-outdoors.mp3"),
        new Track("Monkeys Spinning Monkeys", "Kevin MacLeod", "Funny, happy, viral classic", "Funny, pets, memes, light review", "2:05", "CC BY 4.0, credit required", "Monkeys Spinning Monkeys by Kevin MacLeod, incompetech.com, CC BY 4.0", "https://commons.wikimedia.org/wiki/File:Monkeys_Spinning_Monkeys_by_Kevin_MacLeod.ogg", "https://upload.wikimedia.org/wikipedia/commons/transcoded/f/fe/Monkeys_Spinning_Monkeys_by_Kevin_MacLeod.ogg/Monkeys_Spinning_Monkeys_by_Kevin_MacLeod.ogg.mp3", "monkeys-spinning-monkeys.mp3")
    };
}
