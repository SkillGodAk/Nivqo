/*
 * The sound re-encode is adapted from Hushfacebook's AacReencode.java (same author), 2026.
 */
package app.hushmessenger.extension;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes a video whose only sound is xHE-AAC sendable. Messenger, stock included, refuses such a file ("Unable to
 * send"), and Meta's own media scanner scores it as suspect, so the file itself has to change. Android's AAC decoder
 * has read xHE-AAC since Android 9 and every phone has an AAC-LC encoder, so the sound is decoded and encoded again,
 * the picture samples are copied as they are, and the result replaces Messenger's copy of the shared file.
 *
 * <p>Messenger calls {@link #fixSharedFile} on its background copy thread, right after it closes the copy and before
 * anything reads it. Any failure leaves the copy exactly as it was.
 */
public final class XheAudio {
    static final String KEY = "xhe_audio";

    /** The AAC object type xHE-AAC (USAC) declares, as in {@code mp4a.40.42}. */
    static final int XHE_OBJECT_TYPE = 42;

    /** What the new sound is encoded at. Facebook sends xHE-AAC at 48 to 96 kbps. */
    static final int BIT_RATE = 160_000;

    /** How long one wait on a codec lasts. */
    private static final long WAIT_US = 10_000;

    /** A codec that gives nothing back for this long has stalled. */
    static final long STALL_MS = 10_000;

    /** Rounds with nothing done that also count as a stall, so a codec that answers every wait at once can't spin. */
    static final int STALL_ROUNDS = 3_000;

    /** Room for one picture sample while joining; a larger one throws and the copy stays as it was. */
    static final int SAMPLE_BYTES = 8 << 20;

    private XheAudio() {}

    /**
     * Called by the patched share copy with Messenger's finished copy of a shared item. The copy runs on a background
     * thread; on the main thread a conversion could freeze Messenger, so it's skipped there.
     */
    public static void fixSharedFile(File file) {
        try {
            if (file == null || Looper.myLooper() == Looper.getMainLooper() || !Settings.enabled(KEY)) return;
            fix(file);
        } catch (Throwable error) {
            Settings.hookFailedPrivately(KEY, "Can't convert the shared video's sound", error);
        }
    }

    /**
     * Re-encodes [file]'s sound in place when it needs it. Answers whether the file changed. Only MP4 copies, since
     * the result is an MP4 and Messenger keeps the type it read before the copy.
     */
    static boolean fix(File file) throws IOException {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".mp4") && !name.endsWith(".m4v")) return false;
        if (!needsFix(file)) return false;
        File sound = new File(file.getPath() + ".hush-sound.mp4");
        File joined = new File(file.getPath() + ".hush-joined.mp4");
        try {
            reencode(file, sound);
            join(file, sound, joined);
            if (!joined.renameTo(file)) throw new IOException("the converted video could not replace the copy");
            return true;
        } finally {
            sound.delete();
            joined.delete();
        }
    }

    /**
     * Whether [file] has a picture, at least one sound track, and only xHE-AAC sound. A copy the platform can't parse
     * is none of those, so it stays as it is without counting as a hook failure.
     */
    static boolean needsFix(File file) {
        MediaExtractor source = new MediaExtractor();
        try {
            try {
                source.setDataSource(file.getPath());
            } catch (IOException unreadable) {
                return false;
            }
            boolean picture = false;
            int sounds = 0;
            for (int i = 0; i < source.getTrackCount(); i++) {
                MediaFormat format = source.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) picture = true;
                if (!mime.startsWith("audio/")) continue;
                if (!MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(mime) || objectType(format.getByteBuffer("csd-0")) != XHE_OBJECT_TYPE) {
                    return false;
                }
                sounds++;
            }
            return picture && sounds > 0;
        } finally {
            source.release();
        }
    }

    /**
     * The audio object type an AAC AudioSpecificConfig ({@code csd-0}) declares, or -1. Five bits, and 31 means six
     * more bits follow, counted from 32.
     */
    static int objectType(ByteBuffer config) {
        if (config == null || config.remaining() < 1) return -1;
        ByteBuffer bytes = config.duplicate();
        int first = bytes.get() & 0xff;
        int type = first >>> 3;
        if (type != 31) return type;
        if (!bytes.hasRemaining()) return -1;
        int second = bytes.get() & 0xff;
        return 32 + (((first & 0x07) << 3) | (second >>> 5));
    }

    /**
     * Writes the first sound track of [in] to [out] as AAC-LC, each block stamped from the decoder's own time for it
     * so the sound still lines up with the picture. Throws when a codec can't be had, stalls or fails.
     */
    static void reencode(File in, File out) throws IOException {
        MediaExtractor source = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        Throwable failure = null;

        try {
            source = new MediaExtractor();
            source.setDataSource(in.getPath());
            MediaFormat format = soundTrack(source);
            if (format == null) throw new IOException("the video holds no sound track");
            // 16-bit samples, which the encoder takes as they come.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);

            String decoderName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format);
            decoder = decoderName != null
                ? MediaCodec.createByCodecName(decoderName)
                : MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean sourceDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            boolean endSent = false;
            int track = -1;
            // The block of sound the decoder handed over and the encoder hasn't taken all of yet.
            ByteBuffer pending = null;
            int pendingIndex = -1;
            long pendingTime = 0;
            int frameBytes = 2;
            int sampleRate = 0;
            long lastWork = SystemClock.uptimeMillis();
            int idle = 0;
            int written = 0;

            while (!encoderDone) {
                boolean worked = false;

                // 1. The file's next sample into the decoder.
                if (!sourceDone) {
                    int index = decoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : source.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sourceDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, Math.max(0L, source.getSampleTime()), 0);
                            source.advance();
                        }
                        worked = true;
                    }
                }

                // 2. The decoder's sound into the encoder, as much as each input buffer holds.
                if (pending == null && !decoderDone) {
                    int index = decoder.dequeueOutputBuffer(info, WAIT_US);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat pcm = decoder.getOutputFormat();
                        int rate = pcm.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                        int channels = pcm.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                        if (rate <= 0 || channels <= 0) throw new IOException("the decoder gave no sample rate or channel count");
                        if (encoder == null) {
                            sampleRate = rate;
                            frameBytes = 2 * channels;
                            encoder = startEncoder(rate, channels);
                        } else {
                            sameFormat(rate, channels, sampleRate, frameBytes);
                        }
                        worked = true;
                    } else if (index >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderDone = true;
                        ByteBuffer decoded = decoder.getOutputBuffer(index);
                        if (decoded != null && info.size > 0) {
                            decoded.position(info.offset);
                            decoded.limit(info.offset + info.size);
                            pending = decoded;
                            pendingIndex = index;
                            pendingTime = Math.max(0L, info.presentationTimeUs);
                        } else {
                            decoder.releaseOutputBuffer(index, false);
                        }
                        worked = true;
                    }
                }
                if (encoder == null) {
                    if (decoderDone) throw new IOException("the decoder gave no sound format");
                    idle = worked ? 0 : idle + 1;
                    if (worked) lastWork = SystemClock.uptimeMillis();
                    else stalled(lastWork, idle, "the decoder");
                    continue;
                }
                if (pending != null) {
                    int index = encoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        ByteBuffer into = encoder.getInputBuffer(index);
                        if (into == null) throw new IOException("the encoder gave no input buffer");
                        into.clear();
                        int take = Math.min(into.remaining(), pending.remaining());
                        take -= take % frameBytes;
                        if (take <= 0) throw new IOException("the encoder's input buffer holds no whole frame");
                        long time = pendingTime;
                        ByteBuffer part = pending.duplicate();
                        part.limit(part.position() + take);
                        into.put(part);
                        pending.position(pending.position() + take);
                        pendingTime += take / frameBytes * 1_000_000L / sampleRate;
                        encoder.queueInputBuffer(index, 0, take, time, 0);
                        if (!pending.hasRemaining()) {
                            decoder.releaseOutputBuffer(pendingIndex, false);
                            pending = null;
                            pendingIndex = -1;
                        }
                        worked = true;
                    }
                } else if (decoderDone && !endSent) {
                    int index = encoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, pendingTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        endSent = true;
                        worked = true;
                    }
                }

                // 3. The encoder's AAC into the new file.
                int index = encoder.dequeueOutputBuffer(info, WAIT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IOException("the encoder changed its format twice");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                    worked = true;
                } else if (index >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(index);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (encoded != null && info.size > 0 && !config) {
                        if (!muxing) throw new IOException("the encoder gave sound before its format");
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(track, encoded, info);
                        written++;
                    }
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true;
                    worked = true;
                }

                idle = worked ? 0 : idle + 1;
                if (worked) lastWork = SystemClock.uptimeMillis();
                else stalled(lastWork, idle, "the re-encode");
            }
            // A file with no sound in it would join into a silent video.
            if (!muxing || written == 0) throw new IOException("the re-encode made no sound");
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            Throwable closing = failure;
            if (muxer != null) {
                MediaMuxer file = muxer;
                if (muxing) closing = attempt(closing, file::stop);
                closing = attempt(closing, file::release);
            }
            if (encoder != null) {
                MediaCodec codec = encoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (decoder != null) {
                MediaCodec codec = decoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (source != null) closing = attempt(closing, source::release);
            if (failure == null && closing != null) {
                throw new IOException("the re-encoded sound could not be finished: " + closing.getClass().getSimpleName(), closing);
            }
        }
    }

    /**
     * Writes [picture]'s picture tracks and [sound]'s one sound track into [out], each sample as it was, taking the
     * earlier of the two next samples each time so the file stays interleaved. Keeps the picture's rotation.
     */
    static void join(File picture, File sound, File out) throws IOException {
        MediaExtractor pictures = new MediaExtractor();
        MediaExtractor sounds = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxing = false;
        Throwable failure = null;
        try {
            pictures.setDataSource(picture.getPath());
            sounds.setDataSource(sound.getPath());
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation(picture));

            List<Integer> pictureTracks = new ArrayList<>();
            List<Integer> pictureOut = new ArrayList<>();
            for (int i = 0; i < pictures.getTrackCount(); i++) {
                MediaFormat format = pictures.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                pictures.selectTrack(i);
                pictureTracks.add(i);
                pictureOut.add(muxer.addTrack(format));
            }
            if (pictureTracks.isEmpty()) throw new IOException("the video holds no picture track");
            MediaFormat soundFormat = soundTrack(sounds);
            if (soundFormat == null) throw new IOException("the converted sound holds no track");
            int soundOut = muxer.addTrack(soundFormat);
            muxer.start();
            muxing = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BYTES);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                long nextPicture = pictures.getSampleTime();
                long nextSound = sounds.getSampleTime();
                if (nextPicture < 0 && nextSound < 0) break;
                boolean takePicture = nextSound < 0 || (nextPicture >= 0 && nextPicture <= nextSound);
                MediaExtractor from = takePicture ? pictures : sounds;
                int track = takePicture ? pictureOut.get(pictureTracks.indexOf(from.getSampleTrackIndex())) : soundOut;
                buffer.clear();
                int size = from.readSampleData(buffer, 0);
                if (size < 0) break;
                boolean sync = (from.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                info.set(0, size, from.getSampleTime(), sync ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(track, buffer, info);
                from.advance();
            }
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            Throwable closing = failure;
            if (muxer != null) {
                MediaMuxer file = muxer;
                if (muxing) closing = attempt(closing, file::stop);
                closing = attempt(closing, file::release);
            }
            closing = attempt(closing, pictures::release);
            closing = attempt(closing, sounds::release);
            if (failure == null && closing != null) {
                throw new IOException("the converted video could not be finished: " + closing.getClass().getSimpleName(), closing);
            }
        }
    }

    /** The rotation [file]'s picture is shown at, 0 when it says none. */
    static int rotation(File file) {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getPath());
            String value = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int degrees = value == null ? 0 : Integer.parseInt(value.trim());
            return degrees == 90 || degrees == 180 || degrees == 270 ? degrees : 0;
        } catch (RuntimeException unreadable) {
            return 0;
        } finally {
            try { metadata.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * A later format from the decoder has to repeat the first: the encoder was made for that one, and sound at another
     * rate or channel count through it would come out wrong.
     */
    static void sameFormat(int rate, int channels, int sampleRate, int frameBytes) throws IOException {
        if (rate != sampleRate || 2 * channels != frameBytes) throw new IOException("the decoder changed its sound format");
    }

    private static MediaCodec startEncoder(int sampleRate, int channels) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        MediaCodec encoder = name != null
            ? MediaCodec.createByCodecName(name)
            : MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
    }

    private static void stalled(long lastWork, int idle, String what) throws IOException {
        if (SystemClock.uptimeMillis() - lastWork > STALL_MS || idle > STALL_ROUNDS) {
            throw new IOException(what + " stalled for " + STALL_MS / 1000 + " seconds");
        }
    }

    /** Selects the first sound track of [source] and answers its format, or null. */
    private static MediaFormat soundTrack(MediaExtractor source) {
        for (int i = 0; i < source.getTrackCount(); i++) {
            MediaFormat format = source.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                source.selectTrack(i);
                return format;
            }
        }
        return null;
    }

    private interface Step {
        void run() throws Exception;
    }

    private static Throwable attempt(Throwable first, Step step) {
        try {
            step.run();
            return first;
        } catch (Throwable t) {
            return first != null ? first : t;
        }
    }
}
