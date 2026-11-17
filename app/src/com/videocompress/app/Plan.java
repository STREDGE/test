package com.videocompress.app;

/**
 * Decides how to spend the byte budget: which codec, what resolution and what bitrate.
 * Pure arithmetic, no Android classes, so it can be checked on a desktop JVM.
 */
final class Plan {
    static final String HEVC = "video/hevc";
    static final String AVC = "video/avc";

    static final int QUALITY_GREAT = 0;
    static final int QUALITY_GOOD = 1;
    static final int QUALITY_REDUCED = 2;

    /** Share of the target left unused for container overhead and encoder overshoot. */
    private static final double SAFETY = 0.95;
    private static final int MIN_VIDEO_BITRATE = 100_000;
    private static final int MIN_SHORT_SIDE = 240;

    String mime;
    int width;
    int height;
    int videoBitrate;
    int quality;
    /** True when the source already fits and is only re-encoded at its own bitrate. */
    boolean alreadyFits;
    /** Not null when the video cannot be squeezed into the target at all. */
    String error;

    /** Bits per pixel per frame below which the picture starts to fall apart. */
    private static double minBpp(String mime) {
        return HEVC.equals(mime) ? 0.035 : 0.06;
    }

    static Plan make(long fileBytes, long audioBytes, double durationSec, int srcWidth, int srcHeight,
                     int fps, long srcVideoBitrate, long targetBytes, String mime) {
        Plan p = new Plan();
        p.mime = mime;
        p.width = srcWidth;
        p.height = srcHeight;

        double budgetBits = (targetBytes * SAFETY - audioBytes) * 8;
        double bitrate = budgetBits / durationSec;
        if (bitrate < MIN_VIDEO_BITRATE) {
            p.error = "Видео слишком длинное для такого размера. Увеличьте размер на выходе.";
            return p;
        }
        if (fileBytes > 0 && fileBytes <= targetBytes) p.alreadyFits = true;
        if (srcVideoBitrate > 0 && bitrate > srcVideoBitrate) bitrate = srcVideoBitrate;
        // Hardware encoders rarely accept more than this, and nothing here needs it.
        p.videoBitrate = (int) Math.min(bitrate, 100_000_000);

        double threshold = minBpp(mime);
        // Doubling the frame rate does not double the bits needed: neighbouring frames are alike.
        double effectiveFps = fps > 30 ? 30 * Math.sqrt(fps / 30.0) : fps;
        double pixelsPerSec = (double) srcWidth * srcHeight * effectiveFps;
        double scale = Math.min(1.0, Math.sqrt(p.videoBitrate / (threshold * pixelsPerSec)));
        if (scale < 0.97) {
            double minScale = (double) MIN_SHORT_SIDE / Math.min(srcWidth, srcHeight);
            scale = Math.min(1.0, Math.max(scale, minScale));
            p.width = block(srcWidth * scale);
            p.height = block(srcHeight * scale);
        } else {
            scale = 1.0;
        }

        double bpp = p.videoBitrate / ((double) p.width * p.height * effectiveFps);
        if (scale == 1.0 && bpp >= threshold * 1.5) p.quality = QUALITY_GREAT;
        else if (scale >= 0.66 && bpp >= threshold * 0.95) p.quality = QUALITY_GOOD;
        else p.quality = QUALITY_REDUCED;
        return p;
    }

    /** Hardware encoders are happiest with sizes that are whole 16-pixel blocks. */
    private static int block(double v) {
        return Math.max(16, (int) Math.round(v / 16) * 16);
    }
}
