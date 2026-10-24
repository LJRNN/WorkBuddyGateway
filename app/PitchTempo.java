package io.local.wbgateway;

/**
 * 基频（F0）与节拍（BPM）估计。都基于自相关，不依赖额外模型。
 */
final class PitchTempo {

    /** 估计一段 PCM 的基频（Hz），失败返回 0。pcm 需 16kHz 单声道。 */
    static float f0(float[] pcm, int start, int count) {
        if (pcm == null || count < 1024 || start < 0 || start + count > pcm.length) {
            return 0f;
        }
        // 去均值
        double mean = 0;
        for (int i = 0; i < count; i++) {
            mean += pcm[start + i];
        }
        mean /= count;
        float[] x = new float[count];
        double energy = 0;
        for (int i = 0; i < count; i++) {
            x[i] = (float) (pcm[start + i] - mean);
            energy += x[i] * x[i];
        }
        if (energy < 1e-6) {
            return 0f;
        }

        int minLag = 16000 / 500;   // 500Hz
        int maxLag = 16000 / 60;    // 60Hz
        if (maxLag >= count) {
            maxLag = count - 1;
        }
        float best = 0;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < count; i++) {
                sum += x[i] * x[i + lag];
            }
            float r = (float) (sum / energy);
            if (r > best) {
                best = r;
                bestLag = lag;
            }
        }
        if (bestLag == 0 || best < 0.30f) {
            return 0f;
        }
        return 16000f / bestLag;
    }

    /** 粗略节拍估计（BPM），失败返回 0。仅对有明显鼓点的音乐可靠。 */
    static float bpm(float[] pcm) {
        if (pcm == null || pcm.length < 16000 * 4) {
            return 0f;
        }
        int hop = 160; // 10ms
        int frames = pcm.length / hop;
        if (frames < 200) {
            return 0f;
        }
        float[] env = new float[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            int base = f * hop;
            for (int i = 0; i < hop; i++) {
                float v = pcm[base + i];
                s += v * v;
            }
            env[f] = (float) Math.sqrt(s / hop);
        }
        // 半波整流后的差分，突出起音
        float[] onset = new float[frames];
        for (int f = 1; f < frames; f++) {
            float d = env[f] - env[f - 1];
            onset[f] = d > 0 ? d : 0;
        }
        double mean = 0;
        for (float v : onset) {
            mean += v;
        }
        mean /= frames;
        for (int f = 0; f < frames; f++) {
            onset[f] -= (float) mean;
        }

        int minLag = 30;   // 200 BPM
        int maxLag = 100;  // 60 BPM
        float best = 0;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < frames; i++) {
                sum += onset[i] * onset[i + lag];
            }
            if (sum > best) {
                best = (float) sum;
                bestLag = lag;
            }
        }
        if (bestLag == 0) {
            return 0f;
        }
        // 10ms/帧 → BPM = 60000 / (lag * 10)
        float bpm = 6000f / bestLag;
        while (bpm < 70) {
            bpm *= 2;
        }
        while (bpm > 190) {
            bpm /= 2;
        }
        return Math.round(bpm);
    }
}
