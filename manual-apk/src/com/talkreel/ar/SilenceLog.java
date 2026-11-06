package com.talkreel.ar;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

// Pro: auto silence cut list. When the mic is quiet for 3 seconds
// or more while recording, that segment is logged with start and
// end times. The list is saved as a .txt next to the clip, so
// the creator can cut those parts in any editor. This is the
// honest Pro version: a cut list, not a silent re-encode.
public class SilenceLog {
    public static class Seg {
        public int startSec;
        public int endSec;
        public Seg(int s, int e) { startSec = s; endSec = e; }
    }
    private static List<Seg> segs = new ArrayList<Seg>();
    private static int quietStart = -1;

    public static void reset() {
        segs.clear();
        quietStart = -1;
    }

    public static void tick(int sec, boolean quiet) {
        if (quiet && quietStart < 0) quietStart = sec;
        if (!quiet && quietStart >= 0) {
            if (sec - quietStart >= 3) segs.add(new Seg(quietStart, sec));
            quietStart = -1;
        }
    }

    public static void finish(int endSec) {
        if (quietStart >= 0 && endSec - quietStart >= 3) segs.add(new Seg(quietStart, endSec));
        quietStart = -1;
    }

    public static File write(File clipFile) {
        try {
            String name = clipFile.getName().replace(".mp4", "-cuts.txt");
            File out = new File(clipFile.getParentFile(), name);
            FileWriter w = new FileWriter(out);
            w.write("TalkReel silence cut list\n");
            w.write("Clip: " + clipFile.getName() + "\n");
            if (segs.isEmpty()) w.write("No silence longer than 3 seconds.\n");
            for (Seg s : segs) {
                w.write("Cut " + s.startSec + "s to " + s.endSec + "s (" + (s.endSec - s.startSec) + "s quiet)\n");
            }
            w.close();
            AppState.lastCutFile = out.getAbsolutePath();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static int count() { return segs.size(); }
}
