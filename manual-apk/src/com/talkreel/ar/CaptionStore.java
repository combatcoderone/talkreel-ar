package com.talkreel.ar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Pro: captions saved as an SRT file next to the clip.
// Each caption gets a start time in seconds from Record.
// SRT is the standard format for Reels, Shorts, TikTok and editors.
public class CaptionStore {
    public static class Entry {
        public int startSec;
        public int endSec;
        public String text;
        public Entry(int s, int e, String t) { startSec = s; endSec = e; text = t; }
    }
    private static List<Entry> entries = new ArrayList<Entry>();
    private static int lastStart = -1;
    private static String lastText = "";

    public static void reset() {
        entries.clear();
        lastStart = -1;
        lastText = "";
    }

    public static void add(int sec, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (sec == lastStart && text.equals(lastText)) return;
        if (lastStart >= 0 && !lastText.isEmpty()) {
            entries.add(new Entry(lastStart, sec, lastText));
        }
        lastStart = sec;
        lastText = text;
    }

    public static void finish(int endSec) {
        if (lastStart >= 0 && !lastText.isEmpty()) {
            entries.add(new Entry(lastStart, Math.max(endSec, lastStart + 2), lastText));
        }
        lastStart = -1;
        lastText = "";
    }

    public static String toSrt() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            sb.append(i + 1).append("\n");
            sb.append(fmt(e.startSec)).append(" --> ").append(fmt(e.endSec)).append("\n");
            sb.append(e.text).append("\n\n");
        }
        return sb.toString();
    }

    private static String fmt(int sec) {
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        int s = sec % 60;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,000", h, m, s);
    }

    public static File write(File clipFile) {
        try {
            String name = clipFile.getName().replace(".mp4", ".srt");
            File out = new File(clipFile.getParentFile(), name);
            Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
            w.write(toSrt());
            w.close();
            AppState.lastSrtFile = out.getAbsolutePath();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static int count() { return entries.size(); }
}
