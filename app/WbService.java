package io.local.wbgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 前台服务：托管 Go 网关进程，随通知栏常驻。 */
public class WbService extends Service {

    public static final String ACTION_START = "io.local.wbgateway.START";
    public static final String ACTION_STOP = "io.local.wbgateway.STOP";
    public static final String ACTION_RESTART = "io.local.wbgateway.RESTART";

    private static final String CHANNEL_ID = "wb_gateway";
    private static final int NOTIFY_ID = 1001;
    private static final int LOG_MAX = 300;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LOG = new ArrayDeque<String>();
    private static volatile boolean running = false;

    private Process proc;
    private Thread reader;
    private FrameService frameService;

    public static boolean isRunning() {
        return running;
    }

    public static String logText() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            for (String l : LOG) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "网关运行状态", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("WorkBuddy 本地网关的前台服务通知");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        try {
            Gateway.cleanupTmp(this);
            frameService = new FrameService(this);
            frameService.start();
        } catch (Exception e) {
            append("抽帧服务启动失败: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null
                ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopGateway();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFY_ID, buildNotification());
        if (ACTION_RESTART.equals(action)) {
            stopGateway();
        }
        startGateway();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopGateway();
        if (frameService != null) {
            frameService.stop();
            frameService = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startGateway() {
        if (proc != null && proc.isAlive()) {
            return;
        }
        try {
            Gateway.ensureConfig(this);
            // 轻量版没有音频模型：强制退回纯转写，避免每次请求都去调不存在的分类服务。
            if (!Gateway.hasAudioModel(this) && !"asr".equals(Gateway.getAudioMode(this))) {
                try {
                    Gateway.setAudioMode(this, "asr");
                } catch (Exception ignored) {
                }
            }
            ProcessBuilder pb = Gateway.process(
                    this, Gateway.BIN_SERVER, "-config", Gateway.configFile(this).getAbsolutePath());
            final Process p = pb.start();
            proc = p;
            running = true;
            append("启动网关 · " + Gateway.binary(this, Gateway.BIN_SERVER));

            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            append(line);
                        }
                        int code = p.waitFor();
                        if (proc == p) {
                            append("进程退出，code=" + code);
                            running = false;
                        }
                    } catch (Exception e) {
                        // 主动停止/重启时 destroy() 会打断阻塞读，这属于正常现象，不记为错误。
                        if (proc == p) {
                            append("读取进程输出失败: " + e.getMessage());
                            running = false;
                        }
                    }
                }
            }, "wb-gateway-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            append("启动失败: " + e);
            running = false;
        }
    }

    private void stopGateway() {
        Process p = proc;
        proc = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        running = false;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, WbService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("WorkBuddy 网关运行中")
                .setContentText("127.0.0.1:7863 · 点按查看密钥与日志")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi).build())
                .build();
    }

    private void append(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String entry = ts + "  " + line;
        synchronized (LOCK) {
            LOG.addLast(entry);
            while (LOG.size() > LOG_MAX) {
                LOG.removeFirst();
            }
        }
        try {
            File f = Gateway.logFile(this);
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(entry);
                w.write('\n');
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
    }
}
