package io.local.wbgateway;

/**
 * 1024 点实数 FFT（radix-2，迭代实现）。
 * 只用于 PANNs 的 log-mel 前端，不是通用库。
 */
final class Fft {

    private final int n;
    private final int[] rev;
    private final float[] cosT;
    private final float[] sinT;

    Fft(int n) {
        if (Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("n must be power of 2");
        }
        this.n = n;
        this.rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        this.cosT = new float[n / 2];
        this.sinT = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2 * Math.PI * i / n;
            cosT[i] = (float) Math.cos(a);
            sinT[i] = (float) Math.sin(a);
        }
    }

    /** 原地计算复数 FFT；输入是实部 re 与虚部 im，长度必须等于构造时的 n。 */
    void transform(float[] re, float[] im) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; j++) {
                    int tw = j * step;
                    float wr = cosT[tw];
                    float wi = sinT[tw];
                    int a = i + j;
                    int b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }
}
