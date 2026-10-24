package io.local.wbgateway;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.InterpreterApi;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * PANNs CNN14（AudioSet 527 类，多标签）本地推理。
 * 输入是 32kHz 单声道 PCM，10.24 秒窗口；log-mel 在 Java 侧计算，CNN 走 TFLite。
 */
final class PannsModel {

    private static final String TAG = "WbPanns";

    static final int SR = 32000;
    static final int CLIP = 320000;   // 10s
    private static final int NFFT = 1024;
    private static final int HOP = 320;
    private static final int NMEL = 64;
    private static final int FRAMES = 1001;
    private static final int BINS = NFFT / 2 + 1;
    private static final int PAD = NFFT / 2;

    private final Interpreter interp;
    private final String[] labels;
    private final float[] melBasis;   // [BINS][NMEL]
    private final float[] window;     // periodic Hann
    private final Fft fft = new Fft(NFFT);
    private final float[] re = new float[NFFT];
    private final float[] im = new float[NFFT];
    private final ByteBuffer input;
    private final ByteBuffer output;
    private final Object lock = new Object();

    private PannsModel(Interpreter interp, String[] labels, float[] melBasis) {
        this.interp = interp;
        this.labels = labels;
        this.melBasis = melBasis;
        this.window = new float[NFFT];
        for (int i = 0; i < NFFT; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / NFFT));
        }
        this.input = ByteBuffer.allocateDirect(FRAMES * NMEL * 4).order(ByteOrder.nativeOrder());
        this.output = ByteBuffer.allocateDirect(labels.length * 4).order(ByteOrder.nativeOrder());
    }

    static PannsModel create(Context c) throws Exception {
        ByteBuffer model = mapAsset(c, "cnn14.tflite");
        String[] labels = readLines(c, "panns_labels.txt");
        float[] basis = readMelBasis(c, "mel_basis.bin");
        Interpreter.Options opt = new Interpreter.Options();
        opt.setNumThreads(4);
        opt.setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY);
        Interpreter it = new Interpreter(model, opt);
        Log.i(TAG, "panns loaded: labels=" + labels.length + " mel=" + basis.length);
        return new PannsModel(it, labels, basis);
    }

    String label(int i) {
        if (i >= 0 && i < labels.length) {
            return labels[i];
        }
        return "class_" + i;
    }

    int classCount() {
        return labels.length;
    }

    String[] labels() {
        return labels;
    }

    void close() {
        synchronized (lock) {
            try {
                interp.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 对 pcm（32kHz 单声道）从 offset 开始的 10 秒窗口做多标签预测。
     * 返回长度 527 的概率数组；越界部分补零。
     */
    float[] predict(float[] pcm, int offset) {
        float[] logmel = logmel(pcm, offset);
        synchronized (lock) {
            input.clear();
            input.asFloatBuffer().put(logmel);
            input.rewind();
            output.clear();
            interp.run(input, output);
            output.rewind();
            FloatBuffer fb = output.asFloatBuffer();
            float[] prob = new float[labels.length];
            for (int i = 0; i < prob.length; i++) {
                prob[i] = fb.get(i);
            }
            return prob;
        }
    }

    /** 与 torchlibrosa 对齐：reflect pad、periodic Hann、1024 点 FFT、power、mel、10log10。 */
    private float[] logmel(float[] pcm, int offset) {
        float[] out = new float[FRAMES * NMEL];
        for (int t = 0; t < FRAMES; t++) {
            int start = offset + t * HOP - PAD;
            for (int i = 0; i < NFFT; i++) {
                re[i] = reflect(pcm, start + i) * window[i];
                im[i] = 0f;
            }
            fft.transform(re, im);
            int base = t * NMEL;
            for (int m = 0; m < NMEL; m++) {
                out[base + m] = 0f;
            }
            for (int k = 0; k < BINS; k++) {
                float p = re[k] * re[k] + im[k] * im[k];
                if (p < 1e-10f) {
                    p = 1e-10f;
                }
                int mb = k * NMEL;
                for (int m = 0; m < NMEL; m++) {
                    out[base + m] += p * melBasis[mb + m];
                }
            }
            for (int m = 0; m < NMEL; m++) {
                float v = out[base + m];
                if (v < 1e-10f) {
                    v = 1e-10f;
                }
                out[base + m] = (float) (10.0 * Math.log10(v));
            }
        }
        return out;
    }

    private static float reflect(float[] x, int i) {
        int n = x.length;
        if (n == 0) {
            return 0f;
        }
        if (n == 1) {
            return x[0];
        }
        while (i < 0 || i >= n) {
            if (i < 0) {
                i = -i;
            }
            if (i >= n) {
                i = 2 * n - 2 - i;
            }
        }
        return x[i];
    }

    /** assets 中的 tflite 必须不压缩（aapt2 -0 tflite），才能直接 mmap。 */
    private static ByteBuffer mapAsset(Context c, String name) throws IOException {
        AssetFileDescriptor afd = null;
        try {
            afd = c.getAssets().openFd(name);
            FileInputStream fis = afd.createInputStream();
            FileChannel ch = fis.getChannel();
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY,
                    afd.getStartOffset(), afd.getDeclaredLength());
            mb.order(ByteOrder.nativeOrder());
            Log.i(TAG, "mapped " + name + " " + afd.getDeclaredLength() + " bytes");
            return mb;
        } catch (Exception e) {
            Log.w(TAG, "mmap failed, fallback to copy: " + e);
            InputStream in = c.getAssets().open(name);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                byte[] raw = bos.toByteArray();
                ByteBuffer bb = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.nativeOrder());
                bb.put(raw);
                bb.rewind();
                return bb;
            } finally {
                in.close();
            }
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static float[] readMelBasis(Context c, String name) throws IOException {
        InputStream in = c.getAssets().open(name);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 17);
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            byte[] raw = bos.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] src = new float[raw.length / 4];   // [NMEL][BINS]
            bb.asFloatBuffer().get(src);
            if (src.length != NMEL * BINS) {
                throw new IOException("mel_basis size " + src.length);
            }
            float[] out = new float[BINS * NMEL];      // 转置成 [BINS][NMEL]
            for (int m = 0; m < NMEL; m++) {
                for (int k = 0; k < BINS; k++) {
                    out[k * NMEL + m] = src[m * BINS + k];
                }
            }
            return out;
        } finally {
            in.close();
        }
    }

    private static String[] readLines(Context c, String name) throws IOException {
        InputStream in = c.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        List<String> out = new ArrayList<String>();
        for (String s : new String(bos.toByteArray(), "UTF-8").split("\n")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }
}
