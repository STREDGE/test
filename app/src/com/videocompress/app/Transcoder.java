package com.videocompress.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Re-encodes the video track with the hardware codecs and copies the audio track untouched.
 * Everything runs synchronously on the calling thread.
 */
final class Transcoder {
    static final class Cancelled extends Exception {
    }

    interface Progress {
        void onProgress(float fraction);
    }

    private static final long TIMEOUT_US = 10_000;

    private Transcoder() {
    }

    static void transcode(Context ctx, Uri source, File output, VideoInfo info, Plan plan,
                          int videoBitrate, AtomicBoolean cancel, Progress progress) throws Exception {
        MediaExtractor videoIn = new MediaExtractor();
        MediaExtractor audioIn = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        GlBridge gl = null;
        boolean muxerStarted = false;

        try {
            videoIn.setDataSource(ctx, source, null);
            int videoTrack = -1;
            for (int i = 0; i < videoIn.getTrackCount(); i++) {
                String mime = videoIn.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) throw new Exception("В файле нет видеодорожки");
            videoIn.selectTrack(videoTrack);
            MediaFormat inputFormat = videoIn.getTrackFormat(videoTrack);

            MediaFormat audioFormat = null;
            ByteBuffer audioBuffer = null;
            if (info.audioTrack >= 0 && info.audioCopyable) {
                audioIn = new MediaExtractor();
                audioIn.setDataSource(ctx, source, null);
                audioIn.selectTrack(info.audioTrack);
                audioFormat = audioIn.getTrackFormat(info.audioTrack);
                int max = audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                        ? audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
                audioBuffer = ByteBuffer.allocateDirect(Math.max(max, 256 * 1024));
            }

            MediaFormat outputFormat = MediaFormat.createVideoFormat(plan.mime, plan.width, plan.height);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, info.fps);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            encoder = MediaCodec.createEncoderByType(plan.mime);
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            gl = new GlBridge(encoder.createInputSurface(), plan.width, plan.height);
            encoder.start();

            // Rotation is stored as a flag in the output file instead of turning the pixels.
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0);
            if (info.hdr && Build.VERSION.SDK_INT >= 31) {
                // Ask the decoder to tone-map HDR down, otherwise colours come out washed.
                inputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(inputFormat, gl.decoderSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(info.rotation);

            MediaCodec.BufferInfo buf = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo audioBuf = new MediaCodec.BufferInfo();
            int videoOut = -1;
            int audioOut = -1;
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;

            while (!encoderDone) {
                if (cancel.get()) throw new Cancelled();

                // 1. Feed compressed source data to the decoder.
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer in = decoder.getInputBuffer(index);
                        int size = videoIn.readSampleData(in, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, videoIn.getSampleTime(), 0);
                            videoIn.advance();
                        }
                    }
                }

                // 2. Take everything the encoder has finished and write it to the file.
                while (true) {
                    int index = encoder.dequeueOutputBuffer(buf, decoderDone ? TIMEOUT_US : 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new Exception("Кодер сменил формат посреди записи");
                        videoOut = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) audioOut = muxer.addTrack(audioFormat);
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (index < 0) continue;

                    if ((buf.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) buf.size = 0;
                    if (buf.size > 0) {
                        if (!muxerStarted) throw new Exception("Кодер выдал данные раньше формата");
                        muxer.writeSampleData(videoOut, encoder.getOutputBuffer(index), buf);
                        // Keep audio next to the video it belongs to.
                        copyAudio(audioIn, muxer, audioOut, audioBuffer, audioBuf, buf.presentationTimeUs);
                        progress.onProgress(Math.min(1f, buf.presentationTimeUs / (float) info.durationUs));
                    }
                    boolean end = (buf.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(index, false);
                    if (end) {
                        encoderDone = true;
                        break;
                    }
                }

                // 3. Move one decoded frame through OpenGL into the encoder.
                if (!decoderDone && !encoderDone) {
                    int index = decoder.dequeueOutputBuffer(buf, TIMEOUT_US);
                    if (index >= 0) {
                        boolean render = buf.size > 0;
                        boolean end = (buf.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        long timeUs = buf.presentationTimeUs;
                        decoder.releaseOutputBuffer(index, render);
                        if (render) {
                            gl.awaitFrame();
                            gl.drawFrame(timeUs);
                        }
                        if (end) {
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                        }
                    }
                }
            }

            if (!muxerStarted) throw new Exception("Не удалось закодировать ни одного кадра");
            copyAudio(audioIn, muxer, audioOut, audioBuffer, audioBuf, Long.MAX_VALUE);
            muxer.stop();
            muxerStarted = false;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (RuntimeException ignored) {
                }
                decoder.release();
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (RuntimeException ignored) {
                }
                encoder.release();
            }
            if (gl != null) gl.release();
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                }
            }
            videoIn.release();
            if (audioIn != null) audioIn.release();
        }
    }

    /** Copies audio samples with timestamps up to {@code untilUs} straight into the output. */
    private static void copyAudio(MediaExtractor audioIn, MediaMuxer muxer, int track, ByteBuffer buffer,
                                  MediaCodec.BufferInfo info, long untilUs) {
        if (audioIn == null || track < 0) return;
        while (true) {
            long timeUs = audioIn.getSampleTime();
            if (timeUs < 0 || timeUs > untilUs) return;
            buffer.clear();
            int size = audioIn.readSampleData(buffer, 0);
            if (size < 0) return;
            int flags = (audioIn.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            info.set(0, size, timeUs, flags);
            muxer.writeSampleData(track, buffer, info);
            audioIn.advance();
        }
    }
}
