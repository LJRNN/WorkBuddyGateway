package io.local.wbgateway;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 访问本机网关的管理接口。 */
final class Api {

    static final String BASE = "http://127.0.0.1:7863";

    private Api() {
    }

    static JSONObject get(Context c, String path) throws Exception {
        return request(c, "GET", path, null);
    }

    static JSONObject post(Context c, String path, String body) throws Exception {
        return request(c, "POST", path, body == null ? "{}" : body);
    }

    /** 原样返回状态码与响应体，用于诊断（不因 4xx/5xx 抛异常）。 */
    static final class Raw {
        final int code;
        final String body;

        Raw(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    static Raw postRaw(Context c, String path, String body, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + Gateway.apiKey(c));
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        try {
            os.write(body.getBytes("UTF-8"));
        } finally {
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = read(in);
        conn.disconnect();
        return new Raw(code, text);
    }

    private static JSONObject request(Context c, String method, String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + Gateway.apiKey(c));
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            try {
                os.write(body.getBytes("UTF-8"));
            } finally {
                os.close();
            }
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = read(in);
        conn.disconnect();
        JSONObject obj;
        try {
            obj = new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("HTTP " + code + ": " + text);
        }
        if (code >= 400) {
            JSONObject err = obj.optJSONObject("error");
            String msg = err != null ? err.optString("message") : text;
            throw new IOException(msg);
        }
        return obj;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toString("UTF-8");
    }
}
