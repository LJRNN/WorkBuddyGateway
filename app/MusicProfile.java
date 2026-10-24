package io.local.wbgateway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 PANNs 的多标签概率里抽出「音乐画像」：
 * 风格 / 乐器 / 人声 / 情绪，外加基频与节拍。
 * AudioSet 的父类 Music 会吸走大部分分数，所以这里只在子类里取，不报 Music 本身。
 */
final class MusicProfile {

    private static final String[] GENRES = {
            "Pop music", "Rock music", "Hip hop music", "Electronic music", "Jazz",
            "Classical music", "Folk music", "Country", "Blues", "Rhythm and blues",
            "Reggae", "Latin", "Heavy metal", "Punk rock", "Soul music", "Funk",
            "Disco", "Dance music", "Ambient music", "Techno", "House music",
            "Trance music", "Dubstep", "Drum and bass", "Electronica", "Swing music",
            "Bluegrass", "Ska", "Grunge", "Hard rock", "Opera", "Lullaby",
            "Soundtrack music", "Middle Eastern music", "Salsa", "Reggaeton"
    };

    private static final String[] INSTRUMENTS = {
            "Guitar", "Acoustic guitar", "Electric guitar", "Bass guitar",
            "Piano", "Electric piano", "Organ", "Drum kit", "Drum", "Snare drum",
            "Bass drum", "Cymbal", "Percussion", "Synthesizer", "Violin, fiddle",
            "Cello", "Trumpet", "Saxophone", "Flute", "Harmonica", "Accordion",
            "Banjo", "Ukulele", "Harp", "Marimba, xylophone", "Mallet percussion"
    };

    private static final String[] VOCALS = {
            "Singing", "Male singing", "Female singing", "Child singing",
            "Choir", "Rapping", "Humming", "Yodeling", "Synthetic singing"
    };

    private static final String[] MOODS = {
            "Happy music", "Sad music", "Angry music", "Scary music",
            "Tender music", "Exciting music", "Funny music"
    };

    private MusicProfile() {
    }

    /** 返回 null 表示这段音频里没有可描述的音乐成分。 */
    static JSONObject build(String[] labels, float[] prob, float[] pcm16) {
        if (labels == null || prob == null || labels.length != prob.length) {
            return null;
        }
        Map<String, Integer> idx = new HashMap<String, Integer>(labels.length * 2);
        for (int i = 0; i < labels.length; i++) {
            idx.put(labels[i], i);
        }
        JSONArray genres = top(idx, prob, GENRES, 3, 0.06f);
        JSONArray instruments = top(idx, prob, INSTRUMENTS, 3, 0.06f);
        JSONArray vocals = top(idx, prob, VOCALS, 2, 0.08f);
        JSONArray moods = top(idx, prob, MOODS, 2, 0.06f);

        int musicIdx = idx.containsKey("Music") ? idx.get("Music") : -1;
        float musicScore = musicIdx >= 0 ? prob[musicIdx] : 0f;
        if (musicScore < 0.10f && genres.length() == 0 && instruments.length() == 0 && vocals.length() == 0) {
            return null;
        }

        JSONObject out = new JSONObject();
        try {
            out.put("score", round3(musicScore));
            out.put("genres", genres);
            out.put("instruments", instruments);
            out.put("vocals", vocals);
            out.put("moods", moods);
            out.put("instrumental", vocals.length() == 0 && musicScore >= 0.15f);
            float bpm = PitchTempo.bpm(pcm16);
            if (bpm > 0) {
                out.put("bpm", (int) bpm);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONArray top(Map<String, Integer> idx, float[] prob, String[] pool,
                                 int k, float min) {
        List<String> names = new ArrayList<String>();
        List<Float> scores = new ArrayList<Float>();
        for (String name : pool) {
            Integer i = idx.get(name);
            if (i == null) {
                continue;
            }
            float s = prob[i];
            if (s < min) {
                continue;
            }
            int at = 0;
            while (at < scores.size() && scores.get(at) >= s) {
                at++;
            }
            names.add(at, name);
            scores.add(at, s);
            if (names.size() > k) {
                names.remove(names.size() - 1);
                scores.remove(scores.size() - 1);
            }
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < names.size(); i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("label", names.get(i));
                o.put("zh", AudioTagger.zhLabel(names.get(i)));
                o.put("score", round3(scores.get(i)));
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
