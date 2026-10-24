package io.local.wbgateway;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 配置、二进制路径、登录子进程。 */
final class Gateway {

    static final String BIN_SERVER = "libwb2api.so";
    static final String BIN_LOGIN = "libwb2login.so";

    private Gateway() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "gateway");
    }

    static File configFile(Context c) {
        return new File(dir(c), "config.json");
    }

    static File authDir(Context c) {
        return new File(dir(c), "auths");
    }

    static File logFile(Context c) {
        return new File(dir(c), "gateway.log");
    }

    static File stateFile(Context c) {
        return new File(dir(c), "data/state.json");
    }

    /** 清掉上次异常退出遗留的临时视频/音频，并放 .nomedia 防止被相册索引。 */
    static void cleanupTmp(Context c) {
        File tmp = new File(dir(c), "tmp");
        if (!tmp.exists()) {
            tmp.mkdirs();
        }
        File[] files = tmp.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith("wbvideo-") || n.startsWith("wbaudio-")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        try {
            File noMedia = new File(tmp, ".nomedia");
            if (!noMedia.exists()) {
                //noinspection ResultOfMethodCallIgnored
                noMedia.createNewFile();
            }
        } catch (Exception ignored) {
        }
    }

    static synchronized void ensureConfig(Context c) throws Exception {
        File d = dir(c);
        new File(d, "auths").mkdirs();
        new File(d, "data").mkdirs();
        new File(d, "tmp").mkdirs();

        File f = configFile(c);
        if (f.exists() && f.length() > 0) {
            // v1.0 的默认 region=cn 会把国际版凭证过滤掉，升级时迁移为 auto。
            try {
                JSONObject old = new JSONObject(new String(read(f), "UTF-8"));
                boolean changed = false;
                if ("cn".equals(old.optString("region"))) {
                    old.put("region", "auto");
                    changed = true;
                }
                if (!old.has("frame_extractor")) {
                    old.put("frame_extractor", "http://127.0.0.1:7864");
                    changed = true;
                }
                if (!old.has("audio_quality")) {
                    old.put("audio_quality", "fine");
                    changed = true;
                }
                if (!old.has("frame_max_size")) {
                    old.put("frame_count", 0);
                    old.put("frame_max_size", 768);
                    old.put("frame_quality", 80);
                    changed = true;
                }
                if (!old.has("video_asr")) {
                    old.put("video_asr", true);
                    changed = true;
                }
                if (!old.has("audio_mode") || "native".equals(old.optString("audio_mode"))) {
                    // 原生音频已被上游证明不可用，统一迁移到 hybrid（转写 + 本地音效识别）。
                    old.put("audio_mode", "hybrid");
                    changed = true;
                }
                if (changed) {
                    write(f, old.toString(2).getBytes("UTF-8"));
                }
            } catch (Exception ignored) {
            }
            return;
        }

        JSONObject o = new JSONObject();
        o.put("listen", "127.0.0.1:7863");
        o.put("api_key", randomKey());
        o.put("auth_dir", "./auths");
        o.put("state_file", "./data/state.json");
        o.put("region", "auto");
        o.put("product", "cli");
        o.put("frame_extractor", "http://127.0.0.1:7864");
        o.put("audio_mode", "hybrid");
        o.put("audio_quality", "fine");
        o.put("frame_count", 0);
        o.put("frame_max_size", 768);
        o.put("frame_quality", 80);
        o.put("video_asr", true);

        JSONObject cool = new JSONObject();
        cool.put("hard_credit", "12h");
        cool.put("soft_rate", "60s");
        cool.put("err_threshold", 5);
        cool.put("err_cooldown", "10m");
        o.put("cooldown", cool);

        JSONObject sch = new JSONObject();
        sch.put("checkin_hours", new org.json.JSONArray().put(9).put(21));
        sch.put("keepalive_hours", new org.json.JSONArray().put(22));
        o.put("schedule", sch);

        JSONObject up = new JSONObject();
        up.put("timeout_seconds", 120);
        o.put("upstream", up);

        write(f, o.toString(2).getBytes("UTF-8"));
    }

    static String apiKey(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
            return o.optString("api_key", "");
        } catch (Exception e) {
            return "";
        }
    }

    static String getProduct(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
            String p = o.optString("product", "cli");
            return p.isEmpty() ? "cli" : p;
        } catch (Exception e) {
            return "cli";
        }
    }

    static void setProduct(Context c, String product) throws Exception {
        JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
        o.put("product", product);
        write(configFile(c), o.toString(2).getBytes("UTF-8"));
    }

    static String getAudioMode(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
            String m = o.optString("audio_mode", "hybrid");
            if ("native".equals(m) || "asr".equals(m)) {
                return m;
            }
            return "hybrid";
        } catch (Exception e) {
            return "hybrid";
        }
    }

    /** 抽帧档位：simple / standard / high。 */
    static String getFramePreset(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
            int size = o.optInt("frame_max_size", 768);
            int count = o.optInt("frame_count", 0);
            if (size >= 1024) {
                return "high";
            }
            if (size <= 640) {
                return "simple";
            }
            return count > 0 ? "fixed" : "standard";
        } catch (Exception e) {
            return "standard";
        }
    }

    static void setFramePreset(Context c, String preset) throws Exception {
        JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
        if ("simple".equals(preset)) {
            o.put("frame_count", 6);
            o.put("frame_max_size", 640);
            o.put("frame_quality", 70);
        } else if ("high".equals(preset)) {
            o.put("frame_count", 16);
            o.put("frame_max_size", 1024);
            o.put("frame_quality", 88);
        } else {
            o.put("frame_count", 0);
            o.put("frame_max_size", 768);
            o.put("frame_quality", 80);
        }
        write(configFile(c), o.toString(2).getBytes("UTF-8"));
    }

    /** APK 是否内置音频模型（YAMNet）。轻量版没有，只做语音转写。 */
    static boolean hasAudioModel(Context c) {
        InputStream in = null;
        try {
            in = c.getAssets().open("yamnet.tflite");
            return in.read() >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 是否允许局域网访问（listen 为 0.0.0.0）。 */
    static boolean isLanEnabled(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
            return o.optString("listen", "").startsWith("0.0.0.0");
        } catch (Exception e) {
            return false;
        }
    }

    static void setLanEnabled(Context c, boolean on) throws Exception {
        JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
        String cur = o.optString("listen", "127.0.0.1:7863");
        int idx = cur.lastIndexOf(':');
        String port = idx > 0 ? cur.substring(idx + 1) : "7863";
        o.put("listen", (on ? "0.0.0.0:" : "127.0.0.1:") + port);
        write(configFile(c), o.toString(2).getBytes("UTF-8"));
    }

    static String getLoginRegion(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
            return "global".equals(o.optString("login_region", "cn")) ? "global" : "cn";
        } catch (Exception e) {
            return "cn";
        }
    }

    static void setLoginRegion(Context c, String region) throws Exception {
        JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
        o.put("login_region", "global".equals(region) ? "global" : "cn");
        write(configFile(c), o.toString(2).getBytes("UTF-8"));
    }

    static String getAudioQuality(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
            return "fast".equals(o.optString("audio_quality", "fine")) ? "fast" : "fine";
        } catch (Exception e) {
            return "fine";
        }
    }

    static void setAudioQuality(Context c, String q) throws Exception {
        JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
        o.put("audio_quality", q);
        write(configFile(c), o.toString(2).getBytes("UTF-8"));
    }

    static void setAudioMode(Context c, String mode) throws Exception {
        JSONObject o = new JSONObject(new String(read(configFile(c)), "UTF-8"));
        o.put("audio_mode", mode);
        write(configFile(c), o.toString(2).getBytes("UTF-8"));
    }

    static String binary(Context c, String name) {
        return c.getApplicationInfo().nativeLibraryDir + "/" + name;
    }

    static ProcessBuilder process(Context c, String name, String... args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(binary(c, name));
        if (args != null) {
            for (String a : args) {
                cmd.add(a);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir(c));
        pb.redirectErrorStream(true);
        pb.environment().put("TMPDIR", new File(dir(c), "tmp").getAbsolutePath());
        pb.environment().put("HOME", c.getFilesDir().getAbsolutePath());
        return pb;
    }

    /** 同步执行登录工具，返回合并后的输出；非 0 退出码抛异常。 */
    static String runLogin(Context c, String... args) throws Exception {
        Process p = process(c, BIN_LOGIN, args).start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            InputStream in = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("命令超时（90s）");
            }
        } finally {
            try { p.getInputStream().close(); } catch (Exception ignored) { }
        }
        String out = bos.toString("UTF-8");
        if (p.exitValue() != 0) {
            throw new IOException(out.trim().isEmpty() ? ("退出码 " + p.exitValue()) : out.trim());
        }
        return out;
    }

    static String firstHttpUrl(String text) {
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.startsWith("http://") || s.startsWith("https://")) {
                return s;
            }
        }
        return null;
    }

    static String randomKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    static void write(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    static byte[] read(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
