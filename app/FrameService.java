package io.local.wbgateway;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 本地抽帧服务：Go 网关把视频写成临时文件，POST 路径到这里，
 * 这里用 MediaMetadataRetriever 抽帧并返回 base64 JPEG。
 * 只监听 127.0.0.1，只允许读取应用 files/gateway/tmp 下的文件。
 */
final class FrameService {

    private static final String TAG = "WbFrameService";
    static final int PORT = 7864;

    private final Context context;
    private final File tmpDir;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile AudioTagger tagger;
    private volatile String taggerError;

    FrameService(Context c) {
        this.context = c.getApplicationContext();
        this.tmpDir = new File(Gateway.dir(this.context), "tmp");
    }

    void start() {
        try {
            tmpDir.mkdirs();
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
            running = true;
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "wb-frame-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "frame service listening on 127.0.0.1:" + PORT);
        } catch (Exception e) {
            Log.w(TAG, "frame service start failed: " + e);
            closeQuietly();
        }
    }

    void stop() {
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
        }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                }, "wb-frame-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "accept failed: " + e);
                }
            }
        }
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(130000);
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String[] rl = requestLine.split(" ");
            boolean isFrames = rl.length >= 2 && rl[1].startsWith("/frames");
            boolean isAudio = rl.length >= 2 && rl[1].startsWith("/audio");
            if (rl.length < 2 || !"POST".equalsIgnoreCase(rl[0]) || (!isFrames && !isAudio)) {
                respond(sock, 404, error("not found"));
                return;
            }
            int length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0 && "content-length".equalsIgnoreCase(line.substring(0, idx).trim())) {
                    length = Integer.parseInt(line.substring(idx + 1).trim());
                }
            }
            if (length <= 0 || length > (1 << 20)) {
                respond(sock, 400, error("bad content length"));
                return;
            }
            byte[] body = new byte[length];
            int off = 0;
            while (off < length) {
                int n = in.read(body, off, length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            JSONObject req = new JSONObject(new String(body, 0, off, "UTF-8"));
            String path = req.optString("path", "");
            int maxSeconds = req.optInt("max_seconds", 90);
            if (isAudio) {
                boolean fine = req.optBoolean("fine", false);
                JSONObject out = analyzeAudio(path, maxSeconds, fine);
                respond(sock, 200, out);
                return;
            }
            int count = req.optInt("count", 8);
            int max = req.optInt("max", 768);
            int quality = req.optInt("quality", 80);
            boolean wantAudio = req.optBoolean("audio", true);
            JSONObject out = extract(path, count, max, quality, wantAudio);
            respond(sock, 200, out);
        } catch (Exception e) {
            Log.w(TAG, "handle failed: " + e);
            try {
                respond(sock, 500, error(String.valueOf(e.getMessage())));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    private JSONObject analyzeAudio(String path, int maxSeconds, boolean fine) throws Exception {
        String canonical = checkPath(path);
        AudioTagger t = tagger();
        if (t == null) {
            throw new IOException(taggerError == null ? "audio tagger unavailable" : taggerError);
        }
        maxSeconds = Math.max(5, Math.min(180, maxSeconds));
        return t.analyze(canonical, maxSeconds, fine);
    }

    private AudioTagger tagger() {
        AudioTagger t = tagger;
        if (t != null) {
            return t;
        }
        synchronized (this) {
            if (tagger != null) {
                return tagger;
            }
            try {
                tagger = AudioTagger.create(context);
                taggerError = null;
            } catch (Throwable e) {
                taggerError = String.valueOf(e);
                Log.w(TAG, "audio tagger init failed: " + e);
            }
            return tagger;
        }
    }

    private String checkPath(String path) throws IOException {
        File f = new File(path);
        String base = tmpDir.getCanonicalPath() + File.separator;
        String canonical = f.getCanonicalPath();
        if (!canonical.startsWith(base)) {
            throw new SecurityException("path outside tmp dir");
        }
        if (!f.isFile()) {
            throw new IOException("file not found");
        }
        return canonical;
    }

    private JSONObject extract(String path, int count, int max, int quality, boolean wantAudio) throws Exception {
        String canonical = checkPath(path);
        count = Math.max(0, Math.min(24, count));
        max = Math.max(256, Math.min(1280, max));
        quality = Math.max(50, Math.min(95, quality));

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        JSONArray frames = new JSONArray();
        JSONArray times = new JSONArray();
        JSONObject audio = null;
        long durationMs = 0;
        try {
            r.setDataSource(canonical);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                try {
                    durationMs = Long.parseLong(d);
                } catch (NumberFormatException ignored) {
                }
            }
            int rotation = 0;
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rot != null) {
                try {
                    rotation = Integer.parseInt(rot);
                } catch (NumberFormatException ignored) {
                }
            }

            // 先分析音轨：既作为输出，也用来决定在哪些时刻取帧。
            if (wantAudio) {
                try {
                    audio = analyzeAudio(canonical, 60, false);
                } catch (Exception e) {
                    Log.w(TAG, "video audio analyse failed: " + e);
                }
            }

            double durationSec = durationMs > 0 ? durationMs / 1000.0 : 0;
            java.util.List<Double> picks = pickTimes(durationSec, count, audio);
            for (double t : picks) {
                long timeUs = (long) (t * 1000000L);
                Bitmap bmp;
                if (Build.VERSION.SDK_INT >= 27) {
                    bmp = r.getScaledFrameAtTime(timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST, max, max);
                } else {
                    bmp = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    bmp = scale(bmp, max);
                }
                if (bmp == null) {
                    continue;
                }
                if (rotation != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(rotation);
                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                    if (rotated != bmp) {
                        bmp.recycle();
                        bmp = rotated;
                    }
                }
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, jpg);
                bmp.recycle();
                frames.put("data:image/jpeg;base64,"
                        + Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP));
                times.put(round1(t));
            }
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }

        JSONObject out = new JSONObject();
        out.put("frames", frames);
        out.put("times", times);
        out.put("duration_ms", durationMs);
        if (audio != null) {
            JSONArray ev = audio.optJSONArray("events");
            if (ev != null && ev.length() > 0) {
                out.put("audio", audio);
            }
        }
        if (wantAudio) {
            try {
                String track = extractAudioTrack(canonical);
                if (track != null) {
                    out.put("audio_track", track);
                }
            } catch (Exception e) {
                Log.w(TAG, "audio track extract failed: " + e);
            }
        }
        if (frames.length() == 0) {
            out.put("error", "no frame decoded (unsupported codec or corrupt video)");
        }
        return out;
    }

    /**
     * 取帧时间点：先按事件中点铺，再用均匀点补足。
     * count=0 时按时长自适应（4~20 帧）。
     */
    private java.util.List<Double> pickTimes(double duration, int count, JSONObject audio) {
        if (duration <= 0) {
            duration = 10;
        }
        int limit = count;
        if (limit <= 0) {
            limit = (int) Math.round(duration / 6.0);
            limit = Math.max(4, Math.min(20, limit));
        }

        java.util.List<Double> events = new java.util.ArrayList<Double>();
        if (audio != null) {
            JSONArray ev = audio.optJSONArray("events");
            for (int i = 0; ev != null && i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                double mid = (e.optDouble("start", 0) + e.optDouble("end", 0)) / 2;
                if (mid > 0 && mid < duration) {
                    events.add(mid);
                }
            }
        }
        int eventBudget = Math.max(2, limit / 2);
        java.util.List<Double> out = new java.util.ArrayList<Double>();
        for (double t : events) {
            if (out.size() >= eventBudget) {
                break;
            }
            out.add(t);
        }
        int uniform = Math.max(2, limit - out.size());
        for (int i = 0; i < uniform; i++) {
            out.add(duration * (i + 0.5) / uniform);
        }
        java.util.Collections.sort(out);

        java.util.List<Double> uniq = new java.util.ArrayList<Double>();
        for (double t : out) {
            if (t <= 0 || t >= duration) {
                continue;
            }
            if (uniq.isEmpty() || t - uniq.get(uniq.size() - 1) > 0.4) {
                uniq.add(t);
            }
        }
        if (uniq.size() > 24) {
            uniq = new java.util.ArrayList<Double>(uniq.subList(0, 24));
        }
        return uniq;
    }

    /** 把音轨解成 16kHz 单声道 WAV，供 Go 侧调 ASR。返回绝对路径。 */
    private String extractAudioTrack(String path) throws Exception {
        float[] pcm = PcmDecoder.decode(path, 16000, 300);
        if (pcm.length < 8000) {
            return null;
        }
        File f = new File(tmpDir, "wbaudio-" + System.currentTimeMillis() + ".wav");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        int dataLen = pcm.length * 2;
        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes("US-ASCII"));
        hdr.putInt(36 + dataLen);
        hdr.put("WAVE".getBytes("US-ASCII"));
        hdr.put("fmt ".getBytes("US-ASCII"));
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) 1);
        hdr.putInt(16000);
        hdr.putInt(32000);
        hdr.putShort((short) 2);
        hdr.putShort((short) 16);
        hdr.put("data".getBytes("US-ASCII"));
        hdr.putInt(dataLen);
        try {
            fos.write(hdr.array());
            byte[] buf = new byte[16384];
            int bi = 0;
            for (float v : pcm) {
                int sv = (int) (v * 32767f);
                if (sv > 32767) {
                    sv = 32767;
                } else if (sv < -32768) {
                    sv = -32768;
                }
                buf[bi++] = (byte) (sv & 0xff);
                buf[bi++] = (byte) ((sv >> 8) & 0xff);
                if (bi == buf.length) {
                    fos.write(buf, 0, bi);
                    bi = 0;
                }
            }
            if (bi > 0) {
                fos.write(buf, 0, bi);
            }
        } finally {
            fos.close();
        }
        return f.getAbsolutePath();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private Bitmap scale(Bitmap src, int max) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int bigger = Math.max(w, h);
        if (bigger <= max) {
            return src;
        }
        float ratio = (float) max / bigger;
        Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * ratio)),
                Math.max(1, (int) (h * ratio)), true);
        if (out != src) {
            src.recycle();
        }
        return out;
    }

    private JSONObject error(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg == null ? "unknown" : msg);
        } catch (Exception ignored) {
        }
        return o;
    }

    private void respond(Socket sock, int code, JSONObject body) throws IOException {
        byte[] raw = body.toString().getBytes("UTF-8");
        OutputStream out = sock.getOutputStream();
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + raw.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(raw);
        out.flush();
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                bos.write(b);
            }
            if (bos.size() > 16384) {
                throw new IOException("header too long");
            }
        }
        if (b == -1 && bos.size() == 0) {
            return null;
        }
        return bos.toString("UTF-8");
    }
}
