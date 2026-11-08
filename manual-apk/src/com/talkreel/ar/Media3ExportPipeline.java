package com.talkreel.ar;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextOverlay;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional Media3 post-recording export. Camera2/MediaRecorder capture is unchanged. */
@UnstableApi
public final class Media3ExportPipeline {
    private static Transformer activeTransformer;

    private static final class Caption {
        final long startUs;
        final long endUs;
        final String text;
        Caption(long start, long end, String value) {
            startUs = start;
            endUs = end;
            text = value;
        }
    }

    private Media3ExportPipeline() {}

    public static synchronized boolean export(final Activity activity, final File source,
            final File music, final File srt, final android.os.Handler callbackHandler,
            final Runnable onCompleted, final Runnable onFailed) {
        if (activeTransformer != null) {
            Toast.makeText(activity, "An export is already running.", Toast.LENGTH_LONG).show();
            return false;
        }
        if (source == null || !source.isFile() || source.length() == 0) {
            Toast.makeText(activity, "The source video is missing.", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!hasEncoder(MimeTypes.VIDEO_H264)) {
            Toast.makeText(activity, "No compatible H.264 encoder found. Original clip is safe.", Toast.LENGTH_LONG).show();
            return false;
        }

        final File output = new File(source.getParentFile(), removeExtension(source.getName()) + "-final.mp4");
        final File temporary = new File(source.getParentFile(), removeExtension(source.getName()) + "-final.tmp.mp4");
        if (temporary.exists()) temporary.delete();
        // Keep the previous successful export until its replacement is fully verified.

        try {
            List<Caption> captions = srt != null && srt.isFile() ? readSrt(srt) : Collections.<Caption>emptyList();
            List<TextureOverlay> overlays = new ArrayList<TextureOverlay>();
            if (!captions.isEmpty()) overlays.add(new CaptionOverlay(captions));
            List<androidx.media3.common.Effect> videoEffects = new ArrayList<androidx.media3.common.Effect>();
            if (!overlays.isEmpty()) videoEffects.add(new OverlayEffect(overlays));

            EditedMediaItem video = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    .setEffects(new Effects(Collections.emptyList(), videoEffects))
                    .build();
            EditedMediaItemSequence videoSequence =
                    EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(video));

            final Composition composition;
            if (music != null && music.isFile() && music.length() > 0) {
                // Keep music under the microphone track instead of letting it compete
                // at full scale. Apply a conservative 20% gain for mono/stereo sources.
                ChannelMixingAudioProcessor musicGain = new ChannelMixingAudioProcessor();
                for (int channels = 1; channels <= 6; channels++) {
                    ChannelMixingMatrix matrix = ChannelMixingMatrix.createForConstantPower(
                            channels, /* outputChannelCount= */ channels);
                    musicGain.putChannelMixingMatrix(matrix.scaleBy(0.20f));
                }
                Effects musicEffects = new Effects(
                        ImmutableList.of(musicGain), Collections.emptyList());
                EditedMediaItem background = new EditedMediaItem.Builder(
                        MediaItem.fromUri(Uri.fromFile(music)))
                        .setEffects(musicEffects)
                        .build();
                EditedMediaItemSequence musicSequence =
                        EditedMediaItemSequence.withAudioFrom(ImmutableList.of(background))
                                .buildUpon().setIsLooping(true).build();
                composition = new Composition.Builder(videoSequence, musicSequence).build();
            } else {
                composition = new Composition.Builder(videoSequence).build();
            }

            activeTransformer = new Transformer.Builder(activity)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition ignored, ExportResult result) {
                            synchronized (Media3ExportPipeline.class) { activeTransformer = null; }
                            boolean expectAudio = hasAudioTrack(source)
                                    || (music != null && music.isFile() && music.length() > 0);
                            String verificationFailure = verifyMuxedOutput(source, temporary, expectAudio);
                            if (verificationFailure != null) {
                                temporary.delete();
                                Toast.makeText(activity, "Export verification failed: " + verificationFailure
                                        + ". Original recording is unchanged.", Toast.LENGTH_LONG).show();
                                if (callbackHandler != null && onFailed != null) callbackHandler.post(onFailed);
                                return;
                            }
                            if (!publishVerifiedOutput(temporary, output)) {
                                temporary.delete();
                                Toast.makeText(activity, "Export finished, but the verified file could not replace the previous export. Existing files are safe.", Toast.LENGTH_LONG).show();
                                if (callbackHandler != null && onFailed != null) callbackHandler.post(onFailed);
                                return;
                            }
                            AppState.lastFile = output.getAbsolutePath();
                            Toast.makeText(activity, "Final MP4 ready with captions and selected music.", Toast.LENGTH_LONG).show();
                            if (callbackHandler != null && onCompleted != null) callbackHandler.post(onCompleted);
                        }

                        @Override
                        public void onError(Composition ignored, ExportResult result, ExportException exception) {
                            synchronized (Media3ExportPipeline.class) { activeTransformer = null; }
                            temporary.delete();
                            Toast.makeText(activity, "Export failed. Original recording is unchanged: " + exception.getErrorCodeName(), Toast.LENGTH_LONG).show();
                            if (callbackHandler != null && onFailed != null) callbackHandler.post(onFailed);
                        }
                    })
                    .build();
            activeTransformer.start(composition, temporary.getAbsolutePath());
            Toast.makeText(activity, "Export started. Keep TalkReel open until it finishes.", Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception e) {
            activeTransformer = null;
            temporary.delete();
            Toast.makeText(activity, "Export unavailable. Original clip is safe: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Inspect the muxed file before publishing it as the final recording. */
    private static boolean hasAudioTrack(File file) {
        MediaExtractor extractor=new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for(int i=0;i<extractor.getTrackCount();i++){
                String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/"))return true;
            }
        } catch(Exception ignored) { }
        finally { try{extractor.release();}catch(Exception ignored){} }
        return false;
    }
    private static String verifyMuxedOutput(File source,File output,boolean expectAudio) {
        if(output==null||!output.isFile()||output.length()<=0)return "the output file is missing or empty";
        MediaExtractor out=new MediaExtractor(),in=new MediaExtractor();
        try {
            out.setDataSource(output.getAbsolutePath());
            boolean video=false,audio=false;long videoDuration=0L;
            for(int i=0;i<out.getTrackCount();i++){
                MediaFormat format=out.getTrackFormat(i);
                String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")){
                    video=true;
                    if(format.containsKey(MediaFormat.KEY_DURATION))videoDuration=Math.max(videoDuration,format.getLong(MediaFormat.KEY_DURATION));
                }
                if(mime!=null&&mime.startsWith("audio/"))audio=true;
            }
            if(!video)return "the MP4 has no video track";
            if(expectAudio&&!audio)return "the MP4 is missing its expected audio track";
            if(videoDuration<=0L)return "the MP4 video duration is unavailable";
            in.setDataSource(source.getAbsolutePath());long sourceVideoDuration=0L;
            for(int i=0;i<in.getTrackCount();i++){
                MediaFormat format=in.getTrackFormat(i);
                String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")&&format.containsKey(MediaFormat.KEY_DURATION))
                    sourceVideoDuration=Math.max(sourceVideoDuration,format.getLong(MediaFormat.KEY_DURATION));
            }
            if(sourceVideoDuration>0L&&Math.abs(videoDuration-sourceVideoDuration)>Math.max(1500000L,sourceVideoDuration/50L))
                return "the exported video duration differs materially from the source";
            return null;
        } catch(Exception error) { return "the MP4 streams could not be inspected"; }
        finally { try{out.release();}catch(Exception ignored){} try{in.release();}catch(Exception ignored){} }
    }
    /** Preserve an existing final export if the verified replacement cannot be moved. */
    private static boolean publishVerifiedOutput(File temporary,File output) {
        File backup=null;
        if(output.exists()){
            backup=new File(output.getParentFile(),output.getName()+".previous-"+System.nanoTime());
            if(!output.renameTo(backup))return false;
        }
        if(temporary.renameTo(output)){
            if(backup!=null)backup.delete();
            return true;
        }
        if(backup!=null&&!backup.renameTo(output))
            android.util.Log.e("TalkReelExport","Could not restore previous export from "+backup.getAbsolutePath());
        return false;
    }
    private static boolean hasEncoder(String mimeType) {
        try {
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : codecs.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (mimeType.equalsIgnoreCase(type)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static List<Caption> readSrt(File file) throws Exception {
        List<Caption> result = new ArrayList<Caption>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
        String line;
        long start = -1;
        long end = -1;
        StringBuilder text = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            String value = line.trim();
            if (value.contains("-->")) {
                if (start >= 0 && end > start && text.length() > 0) result.add(new Caption(start, end, text.toString()));
                String[] times = value.split("-->");
                start = parseTime(times[0].trim());
                end = parseTime(times[1].trim());
                text.setLength(0);
            } else if (!value.isEmpty() && !value.matches("\\d+")) {
                if (text.length() > 0) text.append("\n");
                text.append(value);
            }
        }
        reader.close();
        if (start >= 0 && end > start && text.length() > 0) result.add(new Caption(start, end, text.toString()));
        return result;
    }

    private static long parseTime(String value) {
        String[] hms = value.replace(',', '.').split(":");
        double seconds = Integer.parseInt(hms[0]) * 3600d
                + Integer.parseInt(hms[1]) * 60d + Double.parseDouble(hms[2]);
        return (long) (seconds * 1_000_000d);
    }

    private static final class CaptionOverlay extends TextOverlay {
        private final List<Caption> captions;
        private final OverlaySettings settings = new StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(0f, -0.78f)
                .setBackgroundFrameAnchor(0f, -0.78f)
                .build();

        CaptionOverlay(List<Caption> entries) { captions = entries; }

        @Override
        public SpannableString getText(long presentationTimeUs) {
            for (Caption caption : captions) {
                if (presentationTimeUs >= caption.startUs && presentationTimeUs <= caption.endUs) {
                    SpannableString text = new SpannableString(caption.text);
                    if (text.length() > 0) {
                        text.setSpan(new ForegroundColorSpan(Color.WHITE), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        text.setSpan(new BackgroundColorSpan(0xB0000000), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    return text;
                }
            }
            return new SpannableString("");
        }

        @Override
        public OverlaySettings getOverlaySettings(long presentationTimeUs) { return settings; }
    }
}
