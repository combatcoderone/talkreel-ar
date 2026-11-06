package com.talkreel.ar;

// Pro: AI Script Coach. On device, no server.
// Scores the script, estimates duration, and gives plain
// advice in English, Persian and Arabic. Full advice is Pro.
// Free shows the score only.
public class ScriptCoach {
    public static class Report {
        public int words;
        public int estSeconds;
        public int hookScore;
        public String advice;
        public Report(int w, int s, int h, String a) { words = w; estSeconds = s; hookScore = h; advice = a; }
    }

    public static Report coach(String script, int secondsRecorded) {
        if (script == null) script = "";
        String s = script.trim();
        int words = s.isEmpty() ? 0 : s.split("\\s+").length;
        int est = (int) Math.round(words * 60.0 / 150.0);
        int hook = AiEngine.hookScore(script, secondsRecorded);
        StringBuilder a = new StringBuilder();
        if (words == 0) a.append("Write a script first. ");
        else if (words < 20) a.append("Very short. Add one clear point. ");
        else if (words > 120) a.append("Long for 60s. Cut to one idea. ");
        else a.append("Good length for a short clip. ");
        String low = s.toLowerCase();
        boolean hasHook = low.startsWith("stop") || low.startsWith("wait") || low.startsWith("how") || low.startsWith("why")
            || s.contains("\u0635\u0628\u0631") || s.contains("\u0686\u0637\u0648\u0631")
            || s.contains("\u0627\u0646\u062a\u0638\u0631") || s.contains("\u0643\u064a\u0641");
        if (!hasHook) a.append("Start with a hook: Stop, Wait, How, Why. ");
        else a.append("Hook looks good. ");
        if (est > 60) a.append("At normal speed this is about " + est + "s. Trim for Reels. ");
        return new Report(words, est, hook, a.toString().trim());
    }

    public static String shortLine(String script) {
        Report r = coach(script, 0);
        if (!AppState.isPro) return "Hook " + r.hookScore + "/100  " + r.words + " words  ~" + r.estSeconds + "s  (Pro: full coach)";
        return "Hook " + r.hookScore + "/100  " + r.words + " words  ~" + r.estSeconds + "s  " + r.advice;
    }
}
