package io.local.wbgateway;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.InterpreterApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地音频事件识别。
 *
 * 快速模式：YAMNet（AudioSet 521 类）逐帧分类 + 时间轴聚合，约 3ms/帧。
 * 精细模式：先由 YAMNet 定位事件，再用 PANNs CNN14（527 类、多标签）对事件窗口精判。
 */
final class AudioTagger {

    private static final String TAG = "WbAudioTagger";

    static final int SAMPLE_RATE = 16000;
    private static final int WIN = 15600;   // 0.975s
    private static final int HOP = 7680;    // 0.48s
    private static final int CLASSES = 521;
    private static final float MIN_SCORE = 0.15f;
    private static final float MIN_EVENT_SCORE = 0.20f;
    private static final int MAX_EVENTS = 10;
    private static final int MAX_PANNS_WINDOWS = 5;

    // 这些类别描述的是声学环境而不是「发生了什么」，不参与事件与 top 排名。
    private static final Set<String> SKIP = new HashSet<String>(java.util.Arrays.asList(
            "Silence",
            "Inside, small room",
            "Inside, large room or hall",
            "Inside, public space",
            "Outside, urban or manmade",
            "Outside, rural or natural",
            "Reverberation",
            "Echo"));

    /**
     * 声音家族：AudioSet 把同类声音拆成很多子类（Laughter/Giggle/Snicker…），
     * 分数被摊薄后单类往往只有 0.05，argmax 永远选不到。家族把子类合并回来。
     * on/off 是双阈值（滞回），sum=true 表示用求和恢复被摊薄的置信度。
     */
    private static final class Fam {
        final String key;
        final String[] keys;
        final float on;
        final float off;
        final boolean sum;
        final boolean background;

        Fam(String key, String[] keys, float on, float off, boolean sum, boolean background) {
            this.key = key;
            this.keys = keys;
            this.on = on;
            this.off = off;
            this.sum = sum;
            this.background = background;
        }
    }

    private static final List<Fam> FAMILIES = buildFamilies();

    private static List<Fam> buildFamilies() {
        List<Fam> l = new ArrayList<Fam>();
        // 匹配时取「最长关键字」，所以 "Drum and bass" 归 music 而不是 instrument。
        l.add(new Fam("speech", new String[]{
                "speech", "conversation", "narration", "hubbub", "babbling", "whisper",
                "male speech", "female speech", "speech noise", "chatter", "crowd",
                "children playing"}, 0.16f, 0.09f, false, false));
        l.add(new Fam("singing", new String[]{
                "singing", "choir", "rapping", "humming", "chant", "mantra", "yodeling",
                "hum", "beatboxing", "opera", "child singing"}, 0.16f, 0.09f, false, false));
        l.add(new Fam("laugh", new String[]{
                "laughter", "giggle", "snicker", "chuckle", "belly laugh", "snort"}, 0.10f, 0.05f, true, false));
        l.add(new Fam("cry", new String[]{
                "crying", "cry", "sobbing", "whimper", "wail"}, 0.08f, 0.04f, true, false));
        l.add(new Fam("scream", new String[]{
                "scream", "yell", "shout", "battle cry", "children shouting", "squeal"}, 0.09f, 0.05f, true, false));
        l.add(new Fam("crowd", new String[]{
                "applause", "clapping", "cheering", "whoop", "finger snapping"}, 0.12f, 0.06f, true, false));
        l.add(new Fam("breath", new String[]{
                "breath", "pant", "gasp", "wheeze", "sigh", "sniff", "cough", "sneeze",
                "hiccup", "throat clearing", "snoring", "groan", "gargling", "burping",
                "stomach rumble", "fart"}, 0.07f, 0.04f, true, false));
        l.add(new Fam("impact", new String[]{
                "smash", "crash", "bang", "thump", "thud", "slap", "smack", "whack",
                "clatter", "crack", "breaking", "gunshot", "machine gun", "explosion",
                "boom", "firework", "firecracker", "cap gun", "shatter", "chink", "clink",
                "clang", "thunk", "whip", "crushing", "crumpling", "tearing", "fusillade",
                "slam", "tap", "squeak", "eruption"}, 0.16f, 0.08f, true, false));
        l.add(new Fam("music", new String[]{
                "music", "pop", "rock", "hip hop", "jazz", "classical", "electronic",
                "techno", "house", "dubstep", "drum and bass", "reggae", "country",
                "folk", "funk", "r&b", "soul", "punk", "metal", "grunge", "emo",
                "indie", "ska", "swing", "gospel", "latin", "salsa", "flamenco",
                "blues", "ambient", "soundtrack", "jingle", "lullaby", "karaoke",
                "acapella", "a capella", "disco", "trance", "chiptune", "bluegrass",
                "afrobeat", "song"}, 0.20f, 0.12f, false, true));
        l.add(new Fam("instrument", new String[]{
                "musical instrument", "piano", "guitar", "drum", "bass guitar", "violin",
                "cello", "trumpet", "trombone", "saxophone", "clarinet", "flute", "organ",
                "synthesizer", "harmonica", "accordion", "banjo", "mandolin", "ukulele",
                "harp", "marimba", "xylophone", "glockenspiel", "vibraphone", "tambourine",
                "cowbell", "wood block", "drum machine", "plucked string", "strum", "sitar",
                "zither", "sampler", "percussion", "rimshot", "timpani", "tabla", "cymbal",
                "hi-hat", "maraca", "gong", "mallet percussion", "steelpan", "orchestra",
                "brass instrument", "french horn", "bowed string", "string section",
                "double bass", "tuning fork", "bagpipes", "didgeridoo", "shofar", "theremin",
                "whistling"}, 0.20f, 0.10f, false, false));
        l.add(new Fam("vehicle", new String[]{
                "vehicle", "car", "truck", "bus", "motorcycle", "scooter", "bicycle",
                "train", "subway", "aircraft", "airplane", "helicopter", "jet", "boat",
                "ship", "engine", "traffic", "skidding", "tire", "air brake",
                "reversing beeps", "rail transport", "propeller", "idling", "accelerating",
                "revving", "vroom", "toot", "honk"}, 0.25f, 0.12f, false, true));
        l.add(new Fam("alarm", new String[]{
                "alarm", "siren", "buzzer", "smoke detector", "fire alarm", "bell",
                "ringtone", "telephone", "doorbell", "beep", "ping", "ding",
                "dial tone", "foghorn", "whistle", "steam whistle"}, 0.30f, 0.15f, false, false));
        l.add(new Fam("nature", new String[]{
                "rain", "thunder", "wind", "water", "stream", "ocean", "waves", "surf",
                "bird", "insect", "cricket", "cicada", "frog", "fire", "rustle", "bubbling",
                "rustling leaves", "steam", "gurgling", "splash", "slosh", "squish", "drip",
                "pour", "trickle", "gush", "fill", "spray", "boiling", "bird song"}, 0.12f, 0.06f, false, true));
        l.add(new Fam("footsteps", new String[]{
                "footsteps", "walk", "run", "clip-clop", "skateboard", "shuffle"}, 0.12f, 0.06f, true, false));
        l.add(new Fam("household", new String[]{
                "door", "knock", "glass", "dishes", "vacuum", "blender", "washing machine",
                "dishwasher", "typing", "keyboard", "clock", "camera", "shutter",
                "scissors", "hammer", "saw", "drill", "zipper", "drawer", "cupboard",
                "cutlery", "chopping", "frying", "microwave", "sink", "bathtub",
                "hair dryer", "toilet", "toothbrush", "keys jangling", "coin", "shaver",
                "typewriter", "writing", "tick", "tick-tock", "sewing machine",
                "mechanical fan", "air conditioning", "cash register", "printer",
                "power tool", "sanding", "filing", "lawn mower", "ratchet", "gears",
                "pulleys", "mechanisms"}, 0.20f, 0.10f, false, false));
        l.add(new Fam("animal", new String[]{
                "dog", "cat", "bark", "meow", "rooster", "cow", "sheep", "horse", "pig",
                "lion", "roar", "animal", "yip", "howl", "bow-wow", "growling", "purr",
                "hiss", "neigh", "moo", "oink", "goat", "bleat", "fowl", "cluck",
                "crowing", "turkey", "gobble", "duck", "quack", "goose", "chirp",
                "tweet", "squawk", "coo", "crow", "caw", "owl", "hoot", "rodents",
                "mouse", "mosquito", "buzz", "bee", "croak", "snake", "whale"}, 0.12f, 0.06f, false, false));
        // 纯噪声/测试音：不当作事件，避免「正弦音 0:00-0:03」这类无意义标注。
        l.add(new Fam("noise", new String[]{
                "white noise", "pink noise", "static", "mains hum", "environmental noise",
                "noise", "distortion", "sine wave", "harmonic", "chirp tone", "sound effect",
                "field recording", "television", "radio", "cacophony", "side tone",
                "sidetone", "pulse", "vibration", "throbbing", "whoosh", "zing", "boing",
                "plop", "rumble", "whir", "creak", "sizzle", "clicking", "clickety-clack",
                "crackle"}, 0.50f, 0.40f, false, false));
        return l;
    }

    private final Context ctx;
    private final Interpreter interp;
    private final String[] labels;
    private final boolean[] skip;
    private final ByteBuffer input;
    private final ByteBuffer output;
    private final Object lock = new Object();
    private volatile PannsModel panns;

    private AudioTagger(Context ctx, Interpreter interp, String[] labels) {
        this.ctx = ctx;
        this.interp = interp;
        this.labels = labels;
        this.skip = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            if (SKIP.contains(labels[i])) {
                skip[i] = true;
            }
        }
        this.input = ByteBuffer.allocateDirect(WIN * 4).order(ByteOrder.nativeOrder());
        this.output = ByteBuffer.allocateDirect(CLASSES * 4).order(ByteOrder.nativeOrder());
    }

    static AudioTagger create(Context c) throws Exception {
        File model = ensureAsset(c, "yamnet.tflite");
        String[] labels = readLabels(c, "yamnet_labels.txt");
        byte[] raw = readAll(model);
        ByteBuffer buf = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.nativeOrder());
        buf.put(raw);
        buf.rewind();
        Interpreter.Options opt = new Interpreter.Options();
        opt.setNumThreads(2);
        // 明确使用 APK 自带的 libtensorflowlite_jni.so，避免回退到系统 GMS 版本。
        opt.setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY);
        Interpreter it = new Interpreter(buf, opt);
        Log.i(TAG, "yamnet loaded: " + raw.length + " bytes, " + labels.length + " labels");
        return new AudioTagger(c.getApplicationContext(), it, labels);
    }

    /** 分析一个音频/视频文件；fine=true 时追加 PANNs 精判。 */
    JSONObject analyze(String path, int maxSeconds, boolean fine) throws Exception {
        long t0 = System.currentTimeMillis();
        float[] pcm32 = PcmDecoder.decode(path, PannsModel.SR, Math.max(5, maxSeconds));
        if (pcm32.length < PannsModel.SR / 4) {
            throw new IOException("no decodable audio (" + pcm32.length + " samples)");
        }
        long decodeMs = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        float[] pcm16 = downsample(pcm32, 2);
        float[][] scores = infer(pcm16);
        List<Event> events = aggregate(scores);
        long yamMs = System.currentTimeMillis() - t1;

        float[] pannsMax = null;
        long pannsMs = 0;
        String model = "yamnet";
        if (fine) {
            long t2 = System.currentTimeMillis();
            PannsModel pm = panns();
            if (pm != null) {
                pannsMax = refineWithPanns(pm, pcm32, events);
                model = "yamnet+panns";
            }
            pannsMs = System.currentTimeMillis() - t2;
        }

        JSONObject out = new JSONObject();
        out.put("model", model);
        out.put("duration_ms", (long) pcm16.length * 1000 / SAMPLE_RATE);
        out.put("frames", scores.length);
        out.put("cost_ms", System.currentTimeMillis() - t0);
        out.put("decode_ms", decodeMs);
        out.put("yamnet_ms", yamMs);
        out.put("panns_ms", pannsMs);

        for (Event e : events) {
            String name = e.pannsLabel != null ? e.pannsLabel : label(e.cls);
            if (e.group == null) {
                e.group = groupOf(name);
            }
            boolean voiced = "speech".equals(e.family) || "singing".equals(e.family)
                    || "laugh".equals(e.family);
            if (voiced) {
                int start = (int) (e.start * SAMPLE_RATE);
                int len = Math.min(2048, pcm16.length - start);
                if (start >= 0 && len >= 1024) {
                    float f0 = PitchTempo.f0(pcm16, start, len);
                    if (f0 > 0) {
                        e.pitch = f0 < 165 ? "low" : (f0 < 250 ? "mid" : "high");
                        if (!isExtremeVoice(name) && !"laugh".equals(e.family)) {
                            e.gender = f0 < 165 ? "male" : "female";
                        }
                    }
                }
            }
        }

        JSONArray arr = new JSONArray();
        StringBuilder sb = new StringBuilder();
        for (Event e : events) {
            JSONObject o = new JSONObject();
            String name = e.pannsLabel != null ? e.pannsLabel : label(e.cls);
            o.put("label", name);
            o.put("group", e.group == null ? "other" : e.group);
            o.put("family", e.family == null ? "other" : e.family);
            if (e.gender != null) {
                o.put("gender", e.gender);
            }
            if (e.pitch != null) {
                o.put("pitch", e.pitch);
            }
            o.put("zh", zhLabel(name));
            o.put("start", round1(e.start));
            o.put("end", round1(e.end));
            o.put("score", round3(e.score));
            if (e.pannsNames != null && e.pannsNames.length > 0) {
                JSONArray tags = new JSONArray();
                for (int i = 0; i < e.pannsNames.length; i++) {
                    JSONObject t = new JSONObject();
                    t.put("zh", zhLabel(e.pannsNames[i]));
                    t.put("label", e.pannsNames[i]);
                    t.put("score", round3(e.pannsScores[i]));
                    tags.put(t);
                }
                o.put("tags", tags);
            }
            arr.put(o);
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(fmtTime(e.start)).append('-').append(fmtTime(e.end)).append(' ')
                    .append(describe(e, name));
        }
        out.put("events", arr);

        JSONArray top = pannsMax != null ? topFromPanns(pannsMax) : topClasses(scores);
        out.put("top", top);
        if (pannsMax != null && panns != null) {
            JSONObject music = MusicProfile.build(panns.labels(), pannsMax, pcm16);
            if (music != null) {
                out.put("music", music);
            }
        }
        out.put("summary", sb.toString());
        return out;
    }

    /** 对每个事件的中点取 10 秒窗口做 PANNs 多标签预测，并把事件标签替换为更可信的类别。 */
    private float[] refineWithPanns(PannsModel pm, float[] pcm32, List<Event> events) {
        float[] agg = new float[pm.classCount()];
        int clip = PannsModel.CLIP;
        Set<Integer> used = new HashSet<Integer>();
        int windows = 0;

        List<int[]> offsets = new ArrayList<int[]>();
        for (Event e : events) {
            int center = (int) (((e.start + e.end) / 2) * PannsModel.SR);
            int off = pcm32.length <= clip ? 0 : Math.max(0, Math.min(pcm32.length - clip, center - clip / 2));
            int key = off / (PannsModel.SR / 2);
            if (used.add(key)) {
                offsets.add(new int[]{off, events.indexOf(e)});
            }
            if (offsets.size() >= MAX_PANNS_WINDOWS) {
                break;
            }
        }
        if (offsets.isEmpty()) {
            offsets.add(new int[]{0, -1});
        }

        for (int[] item : offsets) {
            float[] prob = pm.predict(pcm32, item[0]);
            for (int c = 0; c < agg.length; c++) {
                if (prob[c] > agg[c]) {
                    agg[c] = prob[c];
                }
            }
            int best = argmax(prob, 0.0f);
            String bestLabel = pm.label(best);
            float bestScore = prob[best];
            int[] tagIdx = topIndexes(prob, 4, 0.08f);
            if (item[1] >= 0) {
                Event e = events.get(item[1]);
                int n = 0;
                for (int k = 0; k < tagIdx.length; k++) {
                    if (!SKIP.contains(pm.label(tagIdx[k]))) {
                        n++;
                    }
                }
                e.pannsNames = new String[n];
                e.pannsScores = new float[n];
                int w = 0;
                for (int k = 0; k < tagIdx.length; k++) {
                    String nm = pm.label(tagIdx[k]);
                    if (SKIP.contains(nm)) {
                        continue;
                    }
                    e.pannsNames[w] = nm;
                    e.pannsScores[w] = prob[tagIdx[k]];
                    w++;
                }
                // 只补 YAMNet 没归类的部分：10 秒窗口的整体判断不能覆盖 2 秒的短事件，
                // 那正是「掌声被判成音乐」的根源。
                if (bestScore >= 0.25f && !SKIP.contains(bestLabel) && "other".equals(e.family)) {
                    e.pannsLabel = bestLabel;
                    e.score = Math.max(e.score, bestScore);
                }
            }
            windows++;
        }
        Log.i(TAG, "panns windows=" + windows);
        return agg;
    }

    private static float[] downsample(float[] in, int factor) {
        int outLen = in.length / factor;
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            float s = 0f;
            int base = i * factor;
            for (int k = 0; k < factor; k++) {
                s += in[base + k];
            }
            out[i] = s / factor;
        }
        return out;
    }

    private PannsModel panns() {
        PannsModel p = panns;
        if (p != null) {
            return p;
        }
        synchronized (this) {
            if (panns == null) {
                try {
                    long t = System.currentTimeMillis();
                    panns = PannsModel.create(ctx);
                    Log.i(TAG, "panns ready in " + (System.currentTimeMillis() - t) + "ms");
                } catch (Throwable e) {
                    Log.w(TAG, "panns load failed: " + e);
                }
            }
            return panns;
        }
    }

    private float[][] infer(float[] pcm) {
        int frames = Math.max(1, (pcm.length - WIN) / HOP + 1);
        float[][] scores = new float[frames][CLASSES];
        synchronized (lock) {
            for (int f = 0; f < frames; f++) {
                int off = f * HOP;
                input.clear();
                for (int i = 0; i < WIN; i++) {
                    int idx = off + i;
                    input.putFloat(idx < pcm.length ? pcm[idx] : 0f);
                }
                input.rewind();
                output.clear();
                interp.run(input, output);
                output.rewind();
                FloatBuffer fb = output.asFloatBuffer();
                for (int c = 0; c < CLASSES; c++) {
                    scores[f][c] = fb.get(c);
                }
            }
        }
        return scores;
    }

    /**
     * 帧级分数 → 事件区间。每个家族独立做双阈值检测（onset 高、offset 低），
     * 允许多个家族同时存在（说话 + 背景音乐 + 笑声），不再用 argmax 只留一个。
     */
    private List<Event> aggregate(float[][] scores) {
        int frames = scores.length;
        int nf = FAMILIES.size();

        // 类 → 家族：取最长匹配，避免 "Drum and bass" 被 instrument 抢走。
        int[] clsFam = new int[labels.length];
        java.util.Arrays.fill(clsFam, -1);
        for (int c = 0; c < labels.length; c++) {
            if (skip[c]) {
                continue;
            }
            String name = labels[c].toLowerCase();
            int best = -1;
            int bestLen = 0;
            for (int f = 0; f < nf; f++) {
                for (String k : FAMILIES.get(f).keys) {
                    if (name.contains(k) && k.length() > bestLen) {
                        best = f;
                        bestLen = k.length();
                    }
                }
            }
            clsFam[c] = best;
        }

        // 家族分数：sum=true 的族取 top1 + 0.5×top2（恢复被摊薄的置信度，
        // 又不会像「几十个类各 0.02 求和」那样凭空造出 0.3）。
        float[][] fam = new float[nf][frames];
        float[][] second = new float[nf][frames];
        for (int c = 0; c < labels.length; c++) {
            int f = clsFam[c];
            if (f < 0) {
                continue;
            }
            boolean sum = FAMILIES.get(f).sum;
            for (int t = 0; t < frames; t++) {
                float v = scores[t][c];
                if (!sum) {
                    if (v > fam[f][t]) {
                        fam[f][t] = v;
                    }
                } else if (v > fam[f][t]) {
                    second[f][t] = fam[f][t];
                    fam[f][t] = v;
                } else if (v > second[f][t]) {
                    second[f][t] = v;
                }
            }
        }
        for (int f = 0; f < nf; f++) {
            if (!FAMILIES.get(f).sum) {
                continue;
            }
            for (int t = 0; t < frames; t++) {
                fam[f][t] = Math.min(1f, fam[f][t] + 0.5f * second[f][t]);
            }
        }

        // 纯音/白噪声会误触发电器类（正弦音常被判成电话拨号音 0.5）。
        // 噪声族占主导的帧，直接压掉其他语义事件。
        int noiseIdx = -1;
        for (int f = 0; f < nf; f++) {
            if ("noise".equals(FAMILIES.get(f).key)) {
                noiseIdx = f;
                break;
            }
        }
        if (noiseIdx >= 0) {
            for (int t = 0; t < frames; t++) {
                if (fam[noiseIdx][t] >= 0.70f) {
                    for (int f = 0; f < nf; f++) {
                        if (f != noiseIdx) {
                            fam[f][t] = 0f;
                        }
                    }
                }
            }
        }

        List<Event> events = new ArrayList<Event>();
        for (int f = 0; f < nf; f++) {
            Fam d = FAMILIES.get(f);
            if ("noise".equals(d.key)) {
                continue;
            }
            int t = 0;
            while (t < frames) {
                if (fam[f][t] < d.on) {
                    t++;
                    continue;
                }
                int startFrame = t;
                int last = t;
                float peak = fam[f][t];
                float sum = fam[f][t];
                int count = 1;
                int below = 0;
                int j = t + 1;
                while (j < frames) {
                    float v = fam[f][j];
                    if (v >= d.off) {
                        last = j;
                        if (v > peak) {
                            peak = v;
                        }
                        sum += v;
                        count++;
                        below = 0;
                    } else {
                        below++;
                        if (below > 2) {
                            break;
                        }
                    }
                    j++;
                }
                double startSec = startFrame * (double) HOP / SAMPLE_RATE;
                double endSec = (last * (double) HOP + WIN) / SAMPLE_RATE;
                double dur = endSec - startSec;
                if (dur >= 0.2 && !(dur < 0.45 && peak < 0.35)) {
                    Event e = new Event();
                    e.family = d.key;
                    e.group = d.key;
                    e.start = startSec;
                    e.end = Math.min(endSec, frameTime(frames));
                    e.score = peak;
                    e.avg = sum / Math.max(1, count);
                    events.add(e);
                }
                t = Math.max(j, startFrame + 1);
            }
        }

        // 背景声（音乐/车辆/自然）合成一条，避免一首歌被切成十几段刷屏。
        List<Event> merged = new ArrayList<Event>();
        Map<String, Event> bg = new HashMap<String, Event>();
        for (Event e : events) {
            Fam d = familyOf(e.family);
            if (d != null && d.background) {
                Event cur = bg.get(e.family);
                if (cur == null || (e.end - e.start) > (cur.end - cur.start)) {
                    bg.put(e.family, e);
                }
            } else {
                merged.add(e);
            }
        }
        merged.addAll(bg.values());
        Collections.sort(merged, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                int c = Double.compare(a.start, b.start);
                return c != 0 ? c : Float.compare(b.score, a.score);
            }
        });

        if (merged.size() > MAX_EVENTS) {
            List<Event> keep = new ArrayList<Event>(merged);
            Collections.sort(keep, new Comparator<Event>() {
                @Override
                public int compare(Event a, Event b) {
                    return Float.compare(b.score * (float) (b.end - b.start),
                            a.score * (float) (a.end - a.start));
                }
            });
            keep = new ArrayList<Event>(keep.subList(0, MAX_EVENTS));
            Collections.sort(keep, new Comparator<Event>() {
                @Override
                public int compare(Event a, Event b) {
                    return Double.compare(a.start, b.start);
                }
            });
            merged = keep;
        }
        return merged;
    }

    private static double frameTime(int frames) {
        return (double) ((frames - 1) * HOP + WIN) / SAMPLE_RATE;
    }

    private static Fam familyOf(String key) {
        for (Fam f : FAMILIES) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    private JSONArray topClasses(float[][] scores) throws Exception {
        float[] max = new float[CLASSES];
        for (float[] row : scores) {
            for (int c = 0; c < CLASSES; c++) {
                if (!skip[c] && row[c] > max[c]) {
                    max[c] = row[c];
                }
            }
        }
        Integer[] idx = new Integer[CLASSES];
        for (int i = 0; i < CLASSES; i++) {
            idx[i] = i;
        }
        final float[] m = max;
        java.util.Arrays.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(m[b], m[a]);
            }
        });
        JSONArray arr = new JSONArray();
        for (int k = 0; k < 8 && k < idx.length; k++) {
            if (m[idx[k]] < 0.08f) {
                break;
            }
            JSONObject o = new JSONObject();
            o.put("label", label(idx[k]));
            o.put("zh", zhLabel(label(idx[k])));
            o.put("score", round3(m[idx[k]]));
            arr.put(o);
        }
        return arr;
    }

    private JSONArray topFromPanns(float[] prob) throws Exception {
        Integer[] idx = new Integer[prob.length];
        for (int i = 0; i < prob.length; i++) {
            idx[i] = i;
        }
        final float[] p = prob;
        java.util.Arrays.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(p[b], p[a]);
            }
        });
        JSONArray arr = new JSONArray();
        for (int k = 0; k < idx.length && arr.length() < 8; k++) {
            String name = panns.label(idx[k]);
            if (p[idx[k]] < 0.08f || SKIP.contains(name)) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("label", name);
            o.put("zh", zhLabel(name));
            o.put("score", round3(p[idx[k]]));
            arr.put(o);
        }
        return arr;
    }

    private String label(int idx) {
        if (idx >= 0 && idx < labels.length) {
            return labels[idx];
        }
        return "class_" + idx;
    }

    static String zhLabel(String label) {
        if (label == null) {
            return "";
        }
        String hit = ZH.get(label.toLowerCase());
        return hit != null ? hit : label;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static String fmtTime(double sec) {
        int s = (int) Math.max(0, sec);
        if (s < 60) {
            return "0:" + (s < 10 ? "0" : "") + s;
        }
        int m = s / 60;
        int r = s % 60;
        if (m < 60) {
            return m + ":" + (r < 10 ? "0" : "") + r;
        }
        int h = m / 60;
        return h + ":" + (m % 60 < 10 ? "0" : "") + (m % 60) + ":" + (r < 10 ? "0" : "") + r;
    }

    private static final class Event {
        int cls;
        double start;
        double end;
        float score;
        float avg;
        String pannsLabel;
        String[] pannsNames;
        float[] pannsScores;
        String group;
        String family;   // speech / laugh / impact / music ...
        String gender;   // male / female / null
        String pitch;    // low / mid / high
    }

    private static File ensureAsset(Context c, String name) throws IOException {
        File dir = new File(Gateway.dir(c), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create models dir");
        }
        File dst = new File(dir, name);
        if (dst.exists() && dst.length() > 1024) {
            return dst;
        }
        InputStream in = c.getAssets().open(name);
        File tmp = new File(dir, name + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("cannot install model");
        }
        return dst;
    }

    private static String[] readLabels(Context c, String name) throws IOException {
        InputStream in = c.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        String[] lines = new String(bos.toByteArray(), "UTF-8").split("\n");
        List<String> out = new ArrayList<String>(lines.length);
        for (String s : lines) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static final String[] SPEECH_KEYS = {
            "speech", "singing", "rapping", "choir", "conversation", "narration",
            "shout", "yell", "scream", "whisper", "laugh", "giggle", "chuckle",
            "cry", "whimper", "wail", "moan", "groan", "grunt", "sigh", "breath",
            "pant", "gasp", "wheeze", "cough", "sneeze", "sniff", "babbling",
            "hubbub", "chant", "mantra", "humming", "whistling", "battle cry",
            "hiccup", "burping", "chewing", "gargling", "snort", "throat"
    };

    private static final String[] MUSIC_KEYS = {
            "music", "musical instrument", "guitar", "piano", "organ", "drum",
            "cymbal", "percussion", "bass", "violin", "cello", "trumpet",
            "trombone", "saxophone", "clarinet", "flute", "oboe", "bassoon",
            "harmonica", "accordion", "bagpipes", "banjo", "mandolin", "ukulele",
            "harp", "synthesizer", "sampler", "marimba", "xylophone",
            "glockenspiel", "vibraphone", "maraca", "tambourine", "cowbell",
            "wood block", "singing bowl", "drum machine"
    };

    private static final String[] IMPACT_KEYS = {
            "smash", "crash", "bang", "thump", "slap", "whack", "clang", "crack",
            "gunshot", "machine gun", "artillery", "explosion", "boom", "burst",
            "shatter", "breaking", "clatter", "rattle", "firework", "firecracker",
            "cap gun", "eruption", "chop"
    };

    private static final String[] NATURE_KEYS = {
            "rain", "thunder", "wind", "water", "fire", "bird", "insect", "frog",
            "cricket", "cicada", "ocean", "waves", "surf", "stream", "waterfall",
            "steam", "bubbling", "rustle", "wildfire", "fireplace"
    };

    private static final String[] VEHICLE_KEYS = {
            "vehicle", "car", "engine", "truck", "bus", "motorcycle", "scooter",
            "bicycle", "train", "subway", "aircraft", "airplane", "helicopter",
            "jet", "propeller", "boat", "ship", "siren", "horn", "traffic",
            "skateboard", "skidding", "tire"
    };

    private static final String[] HOUSE_KEYS = {
            "door", "bell", "alarm", "telephone", "ringtone", "dial", "keyboard",
            "typing", "typewriter", "mouse", "click", "tick", "clock", "chime",
            "microwave", "blender", "vacuum", "hair dryer", "shaver", "toothbrush",
            "toilet", "shower", "bathtub", "washing machine", "dishwasher",
            "refrigerator", "air conditioning", "fan", "sewing", "printer",
            "camera", "shutter", "coin", "drawer", "zipper", "scissors", "hammer",
            "saw", "chainsaw", "drill", "jackhammer", "power tool", "lawn mower",
            "trimmer", "whacker", "sanding", "welding"
    };

    private static boolean hasKey(String label, String[] keys) {
        for (String k : keys) {
            if (label.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String groupOf(String label) {
        String l = label.toLowerCase();
        if (hasKey(l, SPEECH_KEYS)) {
            return "speech";
        }
        if (hasKey(l, MUSIC_KEYS)) {
            return "music";
        }
        if (hasKey(l, IMPACT_KEYS)) {
            return "impact";
        }
        if (hasKey(l, NATURE_KEYS)) {
            return "nature";
        }
        if (hasKey(l, VEHICLE_KEYS)) {
            return "vehicle";
        }
        if (hasKey(l, HOUSE_KEYS)) {
            return "household";
        }
        return "other";
    }

    /** 尖叫、叫喊这类音高会冲到几百赫兹，男女重叠，不给性别。 */
    private static boolean isExtremeVoice(String label) {
        String l = label.toLowerCase();
        return l.contains("scream") || l.contains("yell") || l.contains("shout")
                || l.contains("battle cry") || l.contains("children shouting");
    }

    private static String describe(Event e, String label) {
        String zh = familyZh(e.family);
        if (zh == null) {
            zh = zhLabel(label);
        }
        if ("speech".equals(e.family) || "singing".equals(e.family)) {
            if ("male".equals(e.gender)) {
                return zh + "（男）";
            }
            if ("female".equals(e.gender)) {
                return zh + "（女）";
            }
            if ("low".equals(e.pitch)) {
                return zh + "（低音）";
            }
            if ("high".equals(e.pitch)) {
                return zh + "（高音）";
            }
            return zh;
        }
        if ("scream".equals(e.family) || "cry".equals(e.family)) {
            return "high".equals(e.pitch) ? zh + "（高音）" : zh;
        }
        return zh;
    }

    /** 家族的中文名；未识别时返回 null，回退到具体类名。 */
    private static String familyZh(String family) {
        if (family == null) {
            return null;
        }
        switch (family) {
            case "speech":
                return "说话";
            case "singing":
                return "唱歌";
            case "laugh":
                return "笑声";
            case "cry":
                return "哭泣";
            case "scream":
                return "叫喊";
            case "breath":
                return "喘息";
            case "impact":
                return "撞击声";
            case "music":
                return "音乐";
            case "instrument":
                return "乐器声";
            case "vehicle":
                return "车辆声";
            case "alarm":
                return "警报声";
            case "nature":
                return "自然声";
            case "footsteps":
                return "脚步声";
            case "household":
                return "家居声";
            case "animal":
                return "动物声";
            default:
                return null;
        }
    }

    private static int[] topIndexes(float[] prob, int k, float min) {
        Integer[] idx = new Integer[prob.length];
        for (int i = 0; i < prob.length; i++) {
            idx[i] = i;
        }
        final float[] p = prob;
        java.util.Arrays.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(p[b], p[a]);
            }
        });
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < idx.length && out.size() < k; i++) {
            if (p[idx[i]] < min) {
                break;
            }
            out.add(idx[i]);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static int argmax(float[] prob, float min) {
        int best = 0;
        for (int i = 1; i < prob.length; i++) {
            if (prob[i] > prob[best]) {
                best = i;
            }
        }
        return prob[best] >= min ? best : -1;
    }

    private static final Map<String, String> ZH = new HashMap<String, String>();

    private static void z(String label, String zh) {
        ZH.put(label.toLowerCase(), zh);
    }

    static {
        // 人声与语言
        z("Speech", "语音");
        z("Child speech, kid speaking", "儿童说话");
        z("Conversation", "对话");
        z("Narration, monologue", "旁白");
        z("Babbling", "咿呀学语");
        z("Speech synthesizer", "合成语音");
        z("Shout", "喊叫");
        z("Bellow", "吼叫");
        z("Whoop", "欢呼");
        z("Yell", "大叫");
        z("Children shouting", "儿童叫喊");
        z("Screaming", "尖叫");
        z("Whispering", "耳语");
        z("Laughter", "笑声");
        z("Baby laughter", "婴儿笑声");
        z("Giggle", "咯咯笑");
        z("Snicker", "窃笑");
        z("Belly laugh", "大笑");
        z("Chuckle, chortle", "轻笑");
        z("Crying, sobbing", "哭泣");
        z("Baby cry, infant cry", "婴儿哭声");
        z("Whimper", "呜咽");
        z("Wail, moan", "哀号");
        z("Sigh", "叹气");
        z("Singing", "歌声");
        z("Choir", "合唱");
        z("Yodeling", "约德尔唱法");
        z("Chant", "吟唱");
        z("Mantra", "诵经");
        z("Child singing", "童声演唱");
        z("Synthetic singing", "合成演唱");
        z("Rapping", "说唱");
        z("Humming", "哼唱");
        z("Groan", "呻吟");
        z("Grunt", "咕哝");
        z("Whistling", "口哨");
        z("Breathing", "呼吸声");
        z("Wheeze", "喘息");
        z("Snoring", "打鼾");
        z("Gasp", "抽气");
        z("Pant", "喘气");
        z("Cough", "咳嗽");
        z("Throat clearing", "清嗓子");
        z("Sneeze", "打喷嚏");
        z("Sniff", "抽鼻子");
        z("Snort", "喷鼻");
        z("Burping, eructation", "打嗝");
        z("Hiccup", "打嗝");
        z("Chewing, mastication", "咀嚼");
        z("Biting", "咬合");
        z("Gargling", "漱口");
        z("Stomach rumble", "肠胃咕噜");
        z("Heart sounds, heartbeat", "心跳");
        z("Cheering", "欢呼声");
        z("Applause", "掌声");
        z("Clapping", "拍手");
        z("Finger snapping", "打响指");

        // 动物
        z("Dog", "狗");
        z("Bark", "狗叫");
        z("Yip", "犬吠");
        z("Howl", "嚎叫");
        z("Bow-wow", "汪汪叫");
        z("Growling", "低吼");
        z("Whimper (dog)", "狗呜咽");
        z("Cat", "猫");
        z("Meow", "猫叫");
        z("Purr", "猫呼噜");
        z("Hiss", "嘶声");
        z("Caterwaul", "猫叫春");
        z("Livestock, farm animals, working animals", "家畜");
        z("Horse", "马");
        z("Cattle, bovinae", "牛");
        z("Moo", "牛叫");
        z("Sheep", "羊");
        z("Bleat", "羊叫");
        z("Goat", "山羊");
        z("Pig", "猪");
        z("Oink", "猪叫");
        z("Crowing, cock-a-doodle-doo", "打鸣");
        z("Chicken, rooster", "鸡");
        z("Cluck", "咯咯叫");
        z("Duck", "鸭");
        z("Quack", "鸭叫");
        z("Goose", "鹅");
        z("Honk", "雁叫");
        z("Bird", "鸟");
        z("Bird vocalization, bird call, bird song", "鸟鸣");
        z("Chirp, tweet", "啾啾");
        z("Squawk", "鸟尖叫");
        z("Coo", "咕咕");
        z("Pigeon, dove", "鸽子");
        z("Crow", "乌鸦");
        z("Caw", "鸦叫");
        z("Owl", "猫头鹰");
        z("Hoot", "枭叫");
        z("Frog", "青蛙");
        z("Croak", "蛙鸣");
        z("Cricket", "蟋蟀");
        z("Insect", "昆虫");
        z("Mosquito", "蚊子");
        z("Bee, wasp, etc.", "蜂类");
        z("Fly, housefly", "苍蝇");
        z("Buzz", "嗡嗡声");

        // 自然与环境
        z("Rain", "雨声");
        z("Raindrop", "雨滴");
        z("Rain on surface", "雨打表面");
        z("Thunder", "雷声");
        z("Thunderstorm", "雷暴");
        z("Water", "水声");
        z("Water tap, faucet", "水龙头");
        z("Pour", "倒水");
        z("Gurgling", "汩汩水声");
        z("Splash, splatter", "溅水");
        z("Slosh", "晃水");
        z("Drip", "滴水");
        z("Stream", "溪流");
        z("Ocean", "海洋");
        z("Wind", "风声");
        z("Wind noise (microphone)", "风噪");
        z("Rustling leaves", "树叶沙沙");
        z("Rustle", "沙沙声");
        z("Fire", "火声");
        z("Waterfall", "瀑布");
        z("Steam", "蒸汽声");

        // 交通与机械
        z("Vehicle", "车辆");
        z("Car", "汽车");
        z("Car passing by", "汽车驶过");
        z("Engine", "引擎声");
        z("Engine starting", "引擎启动");
        z("Engine knocking", "敲缸声");
        z("Idling", "怠速声");
        z("Accelerating, revving, vroom", "加速轰鸣");
        z("Truck", "卡车");
        z("Bus", "公交车");
        z("Motorcycle", "摩托车");
        z("Bicycle", "自行车");
        z("Train", "火车");
        z("Railroad car, train wagon", "列车车厢");
        z("Train wheels squealing", "车轮尖啸");
        z("Aircraft", "飞机");
        z("Helicopter", "直升机");
        z("Jet engine", "喷气发动机");
        z("Propeller, airscrew", "螺旋桨");
        z("Boat, Water vehicle", "船只");
        z("Ship", "轮船");
        z("Siren", "警笛");
        z("Police car (siren)", "警车警笛");
        z("Ambulance (siren)", "救护车警笛");
        z("Fire engine, fire truck (siren)", "消防车警笛");
        z("Civil defense siren", "防空警报");
        z("Car alarm", "汽车警报");
        z("Tire squeal", "轮胎尖叫");
        z("Skidding", "打滑声");
        z("Air horn, truck horn", "气喇叭");
        z("Train horn", "火车鸣笛");

        // 家居与器物
        z("Door", "门声");
        z("Doorbell", "门铃");
        z("Knock", "敲门声");
        z("Slam", "摔门声");
        z("Squeak", "吱呀声");
        z("Tap", "轻敲声");
        z("Alarm clock", "闹钟");
        z("Alarm", "警报");
        z("Bell", "铃声");
        z("Church bell", "教堂钟声");
        z("Bicycle bell", "自行车铃");
        z("Buzzer", "蜂鸣器");
        z("Smoke detector, smoke alarm", "烟雾报警");
        z("Fire alarm", "火警");
        z("Telephone", "电话");
        z("Ringtone", "手机铃声");
        z("Dial tone", "拨号音");
        z("Busy signal", "忙音");
        z("Keyboard (musical)", "键盘乐器");
        z("Typing", "打字声");
        z("Typewriter", "打字机");
        z("Computer keyboard", "电脑键盘");
        z("Mouse", "鼠标点击");
        z("Clicking", "连续咔嗒");
        z("Tick", "滴答声");
        z("Tick-tock", "钟摆滴答");
        z("Clock", "时钟");
        z("Chime", "报时钟声");
        z("Microwave oven", "微波炉");
        z("Blender", "搅拌机");
        z("Vacuum cleaner", "吸尘器");
        z("Hair dryer", "吹风机");
        z("Electric shaver, electric razor", "电动剃须刀");
        z("Electric toothbrush", "电动牙刷");
        z("Toilet flush", "冲水声");
        z("Bathtub (filling or washing)", "浴缸水声");
        z("Air conditioning", "空调声");
        z("Sewing machine", "缝纫机");
        z("Printer", "打印机");
        z("Camera", "相机");
        z("Coin (dropping)", "硬币声");
        z("Zipper (clothing)", "拉链声");
        z("Scissors", "剪刀声");
        z("Hammer", "锤击声");
        z("Chainsaw", "电锯");
        z("Drill", "电钻");
        z("Jackhammer", "风镐");
        z("Power tool", "电动工具");
        z("Lawn mower", "割草机");
        z("Sanding", "打磨声");

        // 撞击与爆裂
        z("Glass", "玻璃声");
        z("Shatter", "玻璃碎裂");
        z("Breaking", "破碎声");
        z("Smash, crash", "猛烈撞击");
        z("Thump, thud", "闷响");
        z("Bang", "砰然声");
        z("Clang", "金属撞击");
        z("Clatter", "哗啦声");
        z("Rattle", "格格响");
        z("Creak", "嘎吱声");
        z("Crack", "爆裂声");
        z("Gunshot, gunfire", "枪声");
        z("Machine gun", "机枪声");
        z("Artillery fire", "炮击");
        z("Explosion", "爆炸");
        z("Fireworks", "烟花");
        z("Firecracker", "鞭炮");
        z("Cap gun", "玩具枪声");
        z("Boom", "轰响");
        z("Burst, pop", "爆开声");
        z("Eruption", "喷发声");
        z("Wood", "木头声");
        z("Chop", "砍击声");
        z("Rumble", "隆隆声");
        z("Static", "静电噪声");
        z("White noise", "白噪声");
        z("Pink noise", "粉红噪声");
        z("Noise", "噪声");
        z("Environmental noise", "环境噪声");
        z("Silence", "寂静");
        z("Inside, small room", "小房间混响");
        z("Inside, large room or hall", "大厅混响");
        z("Inside, public space", "公共场所混响");
        z("Outside, urban or manmade", "城市户外");
        z("Outside, rural or natural", "自然户外");
        z("Reverberation", "混响");
        z("Echo", "回声");

        // 音乐
        z("Music", "音乐");
        z("Musical instrument", "乐器");
        z("Guitar", "吉他");
        z("Acoustic guitar", "木吉他");
        z("Electric guitar", "电吉他");
        z("Bass guitar", "贝斯");
        z("Piano", "钢琴");
        z("Electric piano", "电钢琴");
        z("Organ", "风琴");
        z("Drum", "鼓");
        z("Drum kit", "架子鼓");
        z("Snare drum", "军鼓");
        z("Bass drum", "底鼓");
        z("Hi-hat", "踩镲");
        z("Cymbal", "镲");
        z("Percussion", "打击乐");
        z("Tambourine", "铃鼓");
        z("Cowbell", "牛铃");
        z("Wood block", "木鱼");
        z("Vibraphone", "颤音琴");
        z("Marimba, xylophone", "马林巴");
        z("Glockenspiel", "钟琴");
        z("Harmonica", "口琴");
        z("Accordion", "手风琴");
        z("Bagpipes", "风笛");
        z("Violin, fiddle", "小提琴");
        z("Cello", "大提琴");
        z("Double bass", "低音提琴");
        z("String section", "弦乐组");
        z("Brass instrument", "铜管乐器");
        z("Trumpet", "小号");
        z("Trombone", "长号");
        z("French horn", "圆号");
        z("Saxophone", "萨克斯");
        z("Clarinet", "单簧管");
        z("Flute", "长笛");
        z("Didgeridoo", "迪吉里杜管");
        z("Sitar", "西塔琴");
        z("Banjo", "班卓琴");
        z("Mandolin", "曼陀林");
        z("Ukulele", "尤克里里");
        z("Harp", "竖琴");
        z("Synthesizer", "合成器");
        z("Sampler", "采样器");
        z("Electronic music", "电子音乐");
        z("Techno", "科技舞曲");
        z("House music", "浩室音乐");
        z("Dubstep", "回响贝斯");
        z("Drum and bass", "鼓打贝斯");
        z("Electronica", "电子乐");
        z("Ambient music", "氛围音乐");
        z("Trance music", "迷幻舞曲");
        z("Electronic dance music", "电子舞曲");
        z("Jingle (music)", "广告短曲");
        z("Soundtrack music", "影视配乐");
        z("Lullaby", "摇篮曲");
        z("Classical music", "古典音乐");
        z("Opera", "歌剧");
        z("Jazz", "爵士乐");
        z("Swing music", "摇摆乐");
        z("Blues", "布鲁斯");
        z("Bluegrass", "蓝草音乐");
        z("Country", "乡村音乐");
        z("Folk music", "民谣");
        z("Middle Eastern music", "中东音乐");
        z("Reggae", "雷鬼");
        z("Ska", "斯卡");
        z("Punk rock", "朋克摇滚");
        z("Heavy metal", "重金属");
        z("Grunge", "垃圾摇滚");
        z("Rock music", "摇滚");
        z("Pop music", "流行音乐");
        z("Hip hop music", "嘻哈");
        z("Soul music", "灵魂乐");
        z("Funk", "放克");
        z("Disco", "迪斯科");
        z("Rhythm and blues", "节奏布鲁斯");
        z("Dance music", "舞曲");
        z("Wedding music", "婚礼音乐");
        z("Happy music", "欢快音乐");
        z("Sad music", "忧伤音乐");
        z("Tender music", "温柔音乐");
        z("Exciting music", "激昂音乐");
        z("Angry music", "愤怒音乐");
        z("Scary music", "恐怖音乐");

        // 补充映射（真实标签名）
        z("Walk, footsteps", "脚步声");
        z("Run", "跑步声");
        z("Skateboard", "滑板声");
        z("Clip-clop", "马蹄声");
        z("Neigh, whinny", "马嘶");
        z("Waves, surf", "海浪");
        z("Crackle", "噼啪声");
        z("Subway, metro, underground", "地铁");
        z("Motorboat, speedboat", "摩托艇");
        z("Sailboat, sailing ship", "帆船");
        z("Reversing beeps", "倒车提示音");
        z("Vehicle horn, car horn, honking", "汽车喇叭");
        z("Drawer open or close", "抽屉声");
        z("Sawing", "锯木声");
        z("Maraca", "沙锤");
        z("Sink (filling or washing)", "水槽水声");
        z("Mechanical fan", "风扇声");
        z("Traffic noise, roadway noise", "交通噪声");
        z("Motor vehicle (road)", "机动车");
        z("Emergency vehicle", "应急车辆");
        z("Hubbub, speech noise, speech babble", "嘈杂人声");
        z("Honk", "鸣笛");
        z("Foghorn", "雾笛");
        z("Singing bowl", "颂钵");
        z("Drum machine", "鼓机");

        // PANNs 比 YAMNet 多出的 6 类
        z("Male speech, man speaking", "男声说话");
        z("Female speech, woman speaking", "女声说话");
        z("Male singing", "男声演唱");
        z("Female singing", "女声演唱");
        z("Battle cry", "战吼");
        z("Funny music", "滑稽音乐");

    }
}
