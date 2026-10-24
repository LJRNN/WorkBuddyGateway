package io.local.wbgateway;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * 把任意音频/视频文件的音轨解码成 16kHz 单声道 float PCM。
 * 用 MediaExtractor + MediaCodec，不依赖 ffmpeg。
 */
final class PcmDecoder {

    private static final int TIMEOUT_US = 10000;

    static float[] decode(String path, int dstRate, int maxSeconds) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(path);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) {
                return new float[0];
            }
            ex.selectTrack(track);

            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : dstRate;
            boolean floatPcm = false;
            if (fmt.containsKey("pcm-encoding")) {
                try {
                    floatPcm = fmt.getInteger("pcm-encoding") == 4; // ENCODING_PCM_FLOAT
                } catch (Exception ignored) {
                }
            }

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            FloatList mono = new FloatList(Math.min(dstRate * Math.max(1, maxSeconds), 1 << 22));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int limit = dstRate * Math.max(1, maxSeconds);
            double srcPerDst = (double) srcRate / dstRate;
            long produced = 0; // 已产出的源采样点数（单声道）

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        if (floatPcm) {
                            java.nio.FloatBuffer fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            while (fb.hasRemaining()) {
                                float sum = 0;
                                int got = 0;
                                for (int c = 0; c < channels && fb.hasRemaining(); c++) {
                                    sum += fb.get();
                                    got++;
                                }
                                if (got > 0) {
                                    mono.add(sum / got);
                                    produced++;
                                }
                            }
                        } else {
                            ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                            while (sb.hasRemaining()) {
                                int sum = 0;
                                int got = 0;
                                for (int c = 0; c < channels && sb.hasRemaining(); c++) {
                                    sum += sb.get();
                                    got++;
                                }
                                if (got > 0) {
                                    mono.add(sum / (got * 32768f));
                                    produced++;
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    if (produced > (long) limit * srcPerDst) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        srcPerDst = (double) srcRate / dstRate;
                    }
                }
            }

            float[] src = mono.toArray();
            return resample(src, srcRate, dstRate, limit);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
            }
            try {
                ex.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static float[] resample(float[] in, int srcRate, int dstRate, int limit) {
        if (in.length == 0) {
            return in;
        }
        if (srcRate == dstRate) {
            if (in.length <= limit) {
                return in;
            }
            float[] cut = new float[limit];
            System.arraycopy(in, 0, cut, 0, limit);
            return cut;
        }
        int outLen = (int) ((long) in.length * dstRate / Math.max(1, srcRate));
        outLen = Math.min(outLen, limit);
        float[] out = new float[outLen];
        double step = (double) srcRate / dstRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            if (i0 >= in.length) {
                break;
            }
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = pos - i0;
            out[i] = (float) (in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    private static final class FloatList {
        private float[] buf;
        private int size;

        FloatList(int cap) {
            buf = new float[Math.max(1024, cap)];
        }

        void add(float v) {
            if (size == buf.length) {
                float[] bigger = new float[buf.length + (buf.length >> 1)];
                System.arraycopy(buf, 0, bigger, 0, size);
                buf = bigger;
            }
            buf[size++] = v;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(buf, 0, out, 0, size);
            return out;
        }
    }
}
