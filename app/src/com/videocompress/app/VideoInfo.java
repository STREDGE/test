package com.videocompress.app;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

/** Everything we need to know about the source file before planning the encode. */
final class VideoInfo {
    String name = "video";
    long fileBytes;
    long durationUs;
    int width;
    int height;
    int rotation;
    int fps = 30;
    boolean hdr;
    String videoMime;

    int audioTrack = -1;
    String audioMime;
    /** True when the audio track can be copied into an MP4 as is. */
    boolean audioCopyable;
    long audioBytes;

    double durationSec() {
        return durationUs / 1e6;
    }

    /** Average bitrate of the source video stream, bits per second. */
    long videoBitrate() {
        return (long) (Math.max(0, fileBytes - audioBytes) * 8 / durationSec());
    }

    static VideoInfo analyze(Context ctx, Uri uri) throws Exception {
        VideoInfo info = new VideoInfo();

        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && c.getString(n) != null) info.name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) info.fileBytes = c.getLong(s);
            }
        } catch (RuntimeException ignored) {
            // Some providers do not support queries; the size is recovered below.
        }
        if (info.fileBytes <= 0) {
            try (android.content.res.AssetFileDescriptor fd =
                         ctx.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (fd != null) info.fileBytes = fd.getLength();
            }
        }

        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            int videoTrack = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i;
                if (mime.startsWith("audio/") && info.audioTrack < 0) info.audioTrack = i;
            }
            if (videoTrack < 0) throw new Exception("В файле нет видеодорожки");

            MediaFormat vf = ex.getTrackFormat(videoTrack);
            info.videoMime = vf.getString(MediaFormat.KEY_MIME);
            info.width = vf.getInteger(MediaFormat.KEY_WIDTH);
            info.height = vf.getInteger(MediaFormat.KEY_HEIGHT);
            if (vf.containsKey(MediaFormat.KEY_DURATION)) {
                info.durationUs = vf.getLong(MediaFormat.KEY_DURATION);
            }
            if (info.durationUs <= 0) throw new Exception("Не удалось определить длительность видео");
            if (vf.containsKey(MediaFormat.KEY_ROTATION)) {
                info.rotation = vf.getInteger(MediaFormat.KEY_ROTATION);
            }
            if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int fps;
                try {
                    fps = vf.getInteger(MediaFormat.KEY_FRAME_RATE);
                } catch (ClassCastException e) {
                    fps = Math.round(vf.getFloat(MediaFormat.KEY_FRAME_RATE));
                }
                if (fps >= 1 && fps <= 240) info.fps = fps;
            }
            if (vf.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                int t = vf.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                info.hdr = t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG;
            }

            if (info.audioTrack >= 0) {
                MediaFormat af = ex.getTrackFormat(info.audioTrack);
                info.audioMime = af.getString(MediaFormat.KEY_MIME);
                info.audioCopyable = MediaFormat.MIMETYPE_AUDIO_AAC.equals(info.audioMime)
                        || MediaFormat.MIMETYPE_AUDIO_AMR_NB.equals(info.audioMime)
                        || MediaFormat.MIMETYPE_AUDIO_AMR_WB.equals(info.audioMime);
                if (info.audioCopyable) {
                    if (af.containsKey(MediaFormat.KEY_BIT_RATE) && af.getInteger(MediaFormat.KEY_BIT_RATE) > 0) {
                        info.audioBytes = (long) (af.getInteger(MediaFormat.KEY_BIT_RATE) / 8.0 * info.durationSec());
                    } else {
                        // No declared bitrate: add up the real sample sizes.
                        ex.selectTrack(info.audioTrack);
                        long size;
                        while ((size = ex.getSampleSize()) >= 0) {
                            info.audioBytes += size;
                            ex.advance();
                        }
                    }
                }
            }
        } finally {
            ex.release();
        }
        return info;
    }
}
