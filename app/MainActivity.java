package io.local.wbgateway;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 控制台：概览 / 模型 / 账号 / 任务。 */
public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 1001;
    private static final int REQ_NOTIFY = 1002;
    private static final int REQ_AUDIO = 1003;

    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_MODELS = 1;
    private static final int TAB_ACCOUNTS = 2;
    private static final int TAB_TASKS = 3;

    private static final int C_BG = 0xFFF4F6FB;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF111827;
    private static final int C_MUTED = 0xFF6B7280;
    private static final int C_BLUE = 0xFF2F6BFF;
    private static final int C_GREEN = 0xFF0E9F6E;
    private static final int C_RED = 0xFFDB3B3B;
    private static final int C_GRAY = 0xFF9CA3AF;
    private static final int C_ORANGE = 0xFFD97706;
    /** 次级面：未选中 tab、次按钮、输入框底色。 */
    private static final int C_SURFACE = 0xFFEFF2F8;
    private static final int C_LINE = 0xFFE5E9F0;
    private static final int C_BLUE_SOFT = 0xFFE8EFFF;
    private static final int C_GREEN_SOFT = 0xFFE3F6EE;
    private static final int C_HEAD_FROM = 0xFF2F6BFF;
    private static final int C_HEAD_TO = 0xFF7BA6FF;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout content;
    private View[] pages = new View[4];
    private TextView[] tabViews = new TextView[4];
    private int currentTab = TAB_OVERVIEW;

    private TextView statusPill;
    private TextView logView;
    private TextView accountSummary;
    private TextView modelsHint;
    private TextView taskSummary;
    private TextView productView;
    private TextView audioModeView;
    private TextView audioQualityView;
    private TextView frameView;
    private CheckBox frameAuto;
    private Slider frameCountSlider;
    private Slider frameSizeSlider;
    private Slider frameQualitySlider;
    private TextView frameEstimate;
    private TextView healthView;
    private TextView headerSub;
    private TextView lanStatus;
    private TextView lanAddr;
    private TextView webUrlView;
    private View rootView;
    private LinearLayout modelsBox;
    private LinearLayout accountsBox;
    private LinearLayout tasksBox;
    private Button startBtn;
    private Button stopBtn;
    private Button loginBtn;
    private Button doneBtn;
    private Button importBtn;

    private JSONObject lastStatus;
    private long lastStatusAt;
    private boolean statusBusy;
    private JSONArray lastTasks = new JSONArray();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshLocal();
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Gateway.ensureConfig(this);
        } catch (Exception e) {
            toast("初始化配置失败: " + e.getMessage());
        }
        applyEdgeToEdge();
        rootView = buildUi();
        applyWindowInsets(rootView);
        setContentView(rootView);
        refreshLan();
        requestNotifyPermission();
        switchTab(TAB_OVERVIEW);
        ui.post(ticker);
        if (WbService.isRunning()) {
            loadStatus();
        }
    }

    /** 旋转 / 分屏时 Activity 不重建，需要自己重建布局才能切换单列 / 双列。 */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuildUi();
    }

    private void rebuildUi() {
        int tab = currentTab;
        rootView = buildUi();
        applyWindowInsets(rootView);
        setContentView(rootView);
        refreshLan();
        switchTab(tab);
        if (WbService.isRunning()) {
            loadStatus();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    /** 状态栏/导航栏透明，内容延伸到系统栏下面；图标用深色，配浅色背景。 */
    private void applyEdgeToEdge() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** 把系统栏高度加到根布局 padding，横屏时左右侧的导航栏也一并处理。 */
    private void applyWindowInsets(final View root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /** 宽屏（横屏且宽度 ≥600dp）时页面排成两列。 */
    private boolean isWide() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels && dm.widthPixels / dm.density >= 600f;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(round(C_CARD, 20));
        c.setClipToOutline(true);
        c.setElevation(dp(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(12));
        c.setLayoutParams(p);
        return c;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setOnClickListener(l);
        b.setStateListAnimator(null);
        if (primary) {
            b.setTextColor(0xFFFFFFFF);
            b.setBackground(round(C_BLUE, 12));
        } else {
            b.setTextColor(C_TEXT);
            b.setBackground(round(C_SURFACE, 12));
        }
        b.setMinHeight(dp(46));
        b.setMinimumHeight(dp(46));
        return b;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private ScrollView page(LinearLayout col) {
        ScrollView s = new ScrollView(this);
        col.setPadding(dp(14), dp(14), dp(14), dp(30));
        View body = col;
        if (isWide()) {
            List<View> children = new ArrayList<View>();
            while (col.getChildCount() > 0) {
                View child = col.getChildAt(0);
                col.removeViewAt(0);
                children.add(child);
            }
            LinearLayout outer = row();
            outer.setGravity(Gravity.TOP);
            LinearLayout left = column();
            LinearLayout right = column();
            left.setPadding(dp(6), 0, dp(6), 0);
            right.setPadding(dp(6), 0, dp(6), 0);
            for (int i = 0; i < children.size(); i++) {
                (i % 2 == 0 ? left : right).addView(children.get(i));
            }
            outer.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            outer.addView(right, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            body = outer;
        }
        s.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        s.setBackgroundColor(C_BG);
        return s;
    }

    private View buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(C_BG);

        LinearLayout header = card();
        header.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable headBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{C_HEAD_FROM, C_HEAD_TO});
        headBg.setCornerRadius(dp(24));
        header.setBackground(headBg);

        LinearLayout top = row();
        TextView title = text(Gateway.hasAudioModel(this) ? "WorkBuddy 网关" : "WorkBuddy 网关 · 轻量版",
                21, 0xFFFFFFFF, true);
        title.setLetterSpacing(0.01f);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusPill = text("已停止", 12, C_MUTED, true);
        statusPill.setPadding(dp(12), dp(5), dp(12), dp(5));
        statusPill.setBackground(round(0xFFFFFFFF, 20));
        top.addView(statusPill);
        header.addView(top);

        headerSub = text("", 12, 0xE6FFFFFF, false);
        headerSub.setPadding(0, dp(4), 0, dp(12));
        header.addView(headerSub);

        LinearLayout actions = row();
        startBtn = button("启动网关", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGateway();
            }
        });
        stopBtn = button("停止", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, WbService.class));
                ui.postDelayed(ticker, 300);
            }
        });
        // 渐变底上的按钮：主操作白底蓝字，次操作用半透明白，避免蓝压蓝。
        startBtn.setTextColor(C_BLUE);
        startBtn.setBackground(round(0xFFFFFFFF, 12));
        stopBtn.setTextColor(0xFFFFFFFF);
        stopBtn.setBackground(round(0x33FFFFFF, 12));
        actions.addView(startBtn, weightParams());
        actions.addView(stopBtn, weightParams());
        header.addView(actions);
        root.addView(header);

        LinearLayout tabBar = row();
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(C_SURFACE);
        tabBg.setCornerRadius(dp(14));
        tabBar.setBackground(tabBg);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.setMargins(dp(2), 0, dp(2), dp(10));
        tabBar.setLayoutParams(tabLp);
        String[] names = {"概览", "模型", "账号", "任务"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 14, C_MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
            tabBar.addView(t, weightParams());
            tabViews[i] = t;
        }
        root.addView(tabBar);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pages[TAB_OVERVIEW] = buildOverview();
        pages[TAB_MODELS] = buildModels();
        pages[TAB_ACCOUNTS] = buildAccounts();
        pages[TAB_TASKS] = buildTasks();
        for (View p : pages) {
            p.setVisibility(View.GONE);
            content.addView(p);
        }
        return root;
    }

    private View buildOverview() {
        LinearLayout col = column();

        LinearLayout info = card();
        info.addView(text("接入信息", 15, C_TEXT, true));
        info.addView(text("OpenAI 兼容地址与密钥，填到客户端里即可。", 12, C_MUTED, false));

        info.addView(label("BASE URL", true));
        info.addView(copyRow("http://127.0.0.1:7863/v1"));

        info.addView(label("API KEY", true));
        info.addView(copyRow(Gateway.apiKey(this)));

        info.addView(label("网页客户端", true));
        info.addView(text("浏览器打开即可聊天，Key 在页面里填。", 12, C_MUTED, false));
        LinearLayout webRow = row();
        webRow.addView(button("打开网页版", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl())));
                } catch (Exception e) {
                    toast("没有可用浏览器");
                }
            }
        }), weightParams());
        webRow.addView(button("复制地址", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copySilently(webUrl());
                toast("已复制网页地址");
            }
        }), weightParams());
        info.addView(webRow);
        webUrlView = text("", 12, C_BLUE, true);
        webUrlView.setPadding(0, dp(6), 0, 0);
        info.addView(webUrlView);

        info.addView(label("局域网访问", true));
        lanStatus = text("", 12, C_MUTED, false);
        info.addView(lanStatus);
        LinearLayout lanRow = row();
        lanRow.addView(button("开启 / 关闭", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLan();
            }
        }), weightParams());
        lanRow.addView(button("复制局域网地址", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = localIp();
                if (ip == null) {
                    toast("没有连上 WiFi 或有线网络");
                    return;
                }
                copySilently("http://" + ip + ":7863/v1");
                toast("已复制局域网地址");
            }
        }), weightParams());
        info.addView(lanRow);
        lanAddr = text("", 12, C_BLUE, true);
        lanAddr.setPadding(0, dp(6), 0, 0);
        info.addView(lanAddr);
        col.addView(info);

        // 运行健康：不持唤醒锁，被系统冻结/回收是常态，得让用户看得见。
        LinearLayout health = card();
        health.addView(text("运行健康", 15, C_TEXT, true));
        healthView = text("", 12, C_MUTED, false);
        healthView.setPadding(0, dp(6), 0, dp(2));
        health.addView(healthView);
        health.addView(button("重置计数", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Gateway.resetRuntimeStats(MainActivity.this);
                refreshHealth();
                toast("已重置");
            }
        }));
        col.addView(health);

        LinearLayout acc = card();
        acc.addView(text("账号概览", 15, C_TEXT, true));
        accountSummary = text("未获取", 13, C_MUTED, false);
        accountSummary.setPadding(0, dp(6), 0, dp(6));
        acc.addView(accountSummary);
        Button manage = button("管理账号 / 登录", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_ACCOUNTS);
            }
        });
        acc.addView(manage);
        col.addView(acc);

        LinearLayout diag = card();
        diag.addView(text("诊断", 15, C_TEXT, true));
        diag.addView(text("用一段公网 MP4 测试 step-5-preview 是否接受 video_url。"
                + "会消耗少量积分，最长等 2 分钟。", 11, C_MUTED, false));

        productView = text("当前上游身份：" + productLabel(Gateway.getProduct(this)),
                12, C_BLUE, true);
        productView.setPadding(0, dp(8), 0, dp(4));
        diag.addView(productView);

        LinearLayout prow = row();
        prow.addView(productButton("CLI", "cli"), weightParams());
        prow.addView(productButton("桌面端", "workbuddy"), weightParams());
        prow.addView(productButton("VSCode", "vscode"), weightParams());
        diag.addView(prow);

        if (Gateway.hasAudioModel(this)) {
            audioModeView = text("音频处理：" + audioLabel(Gateway.getAudioMode(this)),
                    12, C_BLUE, true);
            audioModeView.setPadding(0, dp(8), 0, dp(4));
            diag.addView(audioModeView);

            LinearLayout arow = row();
            arow.addView(button("仅转文字", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchAudioMode("asr");
                }
            }), weightParams());
            arow.addView(button("转写+音效", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchAudioMode("hybrid");
                }
            }), weightParams());
            diag.addView(arow);

            audioQualityView = text("音频精度：" + qualityLabel(Gateway.getAudioQuality(this)),
                    12, C_BLUE, true);
            audioQualityView.setPadding(0, dp(8), 0, dp(4));
            diag.addView(audioQualityView);
        } else {
            TextView lite = text("轻量版：未内置音频模型，只做语音转写（无音效/音乐识别）。",
                    12, C_ORANGE, true);
            lite.setPadding(0, dp(8), 0, dp(4));
            diag.addView(lite);
        }

        if (Gateway.hasAudioModel(this)) {
            LinearLayout qrow = row();
            qrow.addView(button("快速（4MB）", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchQuality("fast");
                }
            }), weightParams());
            qrow.addView(button("精细（160MB）", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchQuality("fine");
                }
            }), weightParams());
            diag.addView(qrow);
        }

        diag.addView(divider("视频抽帧"));

        final int[] tune = Gateway.frameTuning(this);
        frameAuto = new CheckBox(this);
        frameAuto.setText("按声音事件自适应帧数");
        frameAuto.setTextSize(13);
        frameAuto.setTextColor(C_TEXT);
        frameAuto.setButtonTintList(ColorStateList.valueOf(C_BLUE));
        frameAuto.setChecked(tune[0] < 0);
        frameAuto.setPadding(0, dp(2), 0, dp(2));
        frameAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                frameCountSlider.setEnabled(!frameAuto.isChecked());
                refreshFrameEstimate();
            }
        });
        diag.addView(frameAuto);

        frameCountSlider = slider("帧数", 4, 24, tune[0] < 0 ? 12 : tune[0], " 帧",
                new IntChange() {
                    @Override
                    public void on(int v) {
                        refreshFrameEstimate();
                    }
                });
        frameCountSlider.setEnabled(tune[0] >= 0);
        diag.addView(frameCountSlider.view);

        frameSizeSlider = slider("单帧分辨率", 480, 1280, tune[1], " px",
                new IntChange() {
                    @Override
                    public void on(int v) {
                        refreshFrameEstimate();
                    }
                });
        diag.addView(frameSizeSlider.view);

        frameQualitySlider = slider("JPEG 质量", 50, 95, tune[2], "",
                new IntChange() {
                    @Override
                    public void on(int v) {
                        refreshFrameEstimate();
                    }
                });
        diag.addView(frameQualitySlider.view);

        frameEstimate = text("", 12, C_MUTED, false);
        frameEstimate.setPadding(0, dp(2), 0, dp(8));
        diag.addView(frameEstimate);
        refreshFrameEstimate();

        diag.addView(button("应用抽帧参数", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyFrameTuning();
            }
        }));

        if (Gateway.hasAudioModel(this)) {
            diag.addView(button("音频分类测试（选文件）", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickAudio();
                }
            }));
        }

        diag.addView(button("视频通路测试", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testVideo();
            }
        }));
        col.addView(diag);

        LinearLayout logs = card();
        LinearLayout logHead = row();
        logHead.addView(text("运行日志", 15, C_TEXT, true), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button full = button("全部", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogDialog();
            }
        });
        logHead.addView(full);
        logs.addView(logHead);

        logView = text("尚未启动。", 11, C_TEXT, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(round(0xFFF8F9FC, 12));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logs.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        col.addView(logs);

        LinearLayout oss = card();
        oss.addView(text("开源与许可", 15, C_TEXT, true));
        oss.addView(text("本应用基于开源项目与公开模型构建（TFLite、YAMNet、PANNs 等）。",
                12, C_MUTED, false));
        oss.addView(button("查看开源组件", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOss();
            }
        }));
        col.addView(oss);

        return page(col);
    }

    private View buildModels() {
        LinearLayout col = column();
        LinearLayout head = card();
        LinearLayout r = row();
        r.addView(text("模型列表", 15, C_TEXT, true), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = button("刷新", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadModels();
            }
        });
        r.addView(refresh);
        head.addView(r);
        modelsHint = text("倍率来自上游模型目录；x0.00 表示免费，— 表示上游未提供。", 11, C_MUTED, false);
        modelsHint.setPadding(0, dp(4), 0, 0);
        head.addView(modelsHint);
        col.addView(head);

        LinearLayout box = card();
        modelsBox = column();
        box.addView(modelsBox);
        col.addView(box);
        return page(col);
    }

    private View buildAccounts() {
        LinearLayout col = column();

        LinearLayout actions = card();
        actions.addView(text("账号管理", 15, C_TEXT, true));
        actions.addView(text("OAuth 登录或导入电脑上的凭证文件。", 11, C_MUTED, false));

        LinearLayout row1 = row();
        loginBtn = button("登录账号", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseLoginRegion();
            }
        });
        doneBtn = button("完成登录", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishLogin();
            }
        });
        row1.addView(loginBtn, weightParams());
        row1.addView(doneBtn, weightParams());
        actions.addView(row1);

        LinearLayout row2 = row();
        importBtn = button("导入凭证", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAuthFile();
            }
        });
        row2.addView(importBtn, weightParams());
        row2.addView(button("刷新积分", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadCredits();
            }
        }), weightParams());
        actions.addView(row2);

        LinearLayout row3 = row();
        row3.addView(button("一键签到", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doCheckin();
            }
        }), weightParams());
        row3.addView(button("重置状态", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        }), weightParams());
        actions.addView(row3);
        col.addView(actions);

        LinearLayout box = card();
        accountsBox = column();
        box.addView(accountsBox);
        col.addView(box);
        return page(col);
    }

    private View buildTasks() {
        LinearLayout col = column();

        LinearLayout head = card();
        head.addView(text("成长任务", 15, C_TEXT, true));
        taskSummary = text("尚未加载", 12, C_MUTED, false);
        taskSummary.setPadding(0, dp(4), 0, dp(6));
        head.addView(taskSummary);

        LinearLayout r = row();
        r.addView(button("刷新", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadTasks();
            }
        }), weightParams());
        r.addView(button("一键领取", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                claimAll();
            }
        }), weightParams());
        head.addView(r);

        head.addView(button("打开任务中心", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.workbuddy.cn/profile/growth-center")));
                } catch (Exception e) {
                    toast("没有可用的浏览器");
                }
            }
        }));

        TextView hint = text("可以领取已完成的任务；需要真实使用行为的任务（如对话、使用专家）请到任务中心完成。", 11, C_MUTED, false);
        hint.setPadding(0, dp(6), 0, 0);
        head.addView(hint);
        col.addView(head);

        LinearLayout box = card();
        tasksBox = column();
        box.addView(tasksBox);
        col.addView(box);
        return page(col);
    }

    private TextView label(String s, boolean topMargin) {
        TextView t = text(s, 11, C_MUTED, false);
        t.setPadding(0, dp(topMargin ? 12 : 4), 0, dp(2));
        return t;
    }

    private LinearLayout copyRow(String value) {
        LinearLayout r = row();
        TextView v = text(value, 13, C_TEXT, false);
        v.setTextIsSelectable(true);
        r.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button b = button("复制", false, new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copy(value);
            }
        });
        r.addView(b);
        return r;
    }

    private void switchTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabViews[i].setTextColor(i == idx ? C_BLUE : C_MUTED);
            tabViews[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabViews[i].setBackground(i == idx ? round(C_CARD, 11) : null);
            tabViews[i].setElevation(i == idx ? dp(1) : 0);
        }
        if (idx == TAB_MODELS) {
            loadModels();
        } else if (idx == TAB_ACCOUNTS) {
            loadCredits();
        } else if (idx == TAB_TASKS) {
            loadTasks();
        }
    }

    // --------------------------------------------------------------- status

    private void startGateway() {
        try {
            Gateway.ensureConfig(this);
        } catch (Exception e) {
            show("初始化失败", String.valueOf(e.getMessage()));
            return;
        }
        Intent i = new Intent(this, WbService.class).setAction(WbService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        ui.postDelayed(ticker, 400);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                loadStatus();
            }
        }, 800);
    }

    private void refreshLocal() {
        boolean run = WbService.isRunning();
        statusPill.setText(run ? "● 运行中" : "○ 已停止");
        statusPill.setTextColor(run ? C_GREEN : C_MUTED);
        startBtn.setEnabled(!run);
        stopBtn.setEnabled(run);

        String all = WbService.logText();
        if (all.length() == 0) {
            logView.setText("尚未启动。");
        } else {
            String[] lines = all.split("\n");
            int start = Math.max(0, lines.length - 16);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            logView.setText(sb.toString());
        }

        refreshHealth();
        if (run && System.currentTimeMillis() - lastStatusAt > 4000 && !statusBusy) {
            loadStatus();
        }
    }

    /** 心跳超过 90s 没更新 = 被冻结过；boot_count = 服务被系统杀过几次。 */
    private void refreshHealth() {
        if (healthView == null) {
            return;
        }
        boolean run = WbService.isRunning();
        int boots = Gateway.bootCount(this);
        int freezes = Gateway.freezeCount(this);
        long last = Gateway.lastBeat(this);
        StringBuilder sb = new StringBuilder();
        if (!run) {
            sb.append("服务未运行。启动后会在这里显示冻结与重启次数。");
            healthView.setTextColor(C_MUTED);
        } else if (Gateway.beatStale(this)) {
            long gap = (System.currentTimeMillis() - last) / 1000;
            sb.append("⚠ 已 ").append(gap).append(" 秒没有心跳，服务可能被系统冻结。\n");
            sb.append("把本应用加入「电池优化白名单 / 自启动」后通常就没问题了。");
            healthView.setTextColor(C_ORANGE);
        } else {
            sb.append("运行正常，每 30 秒一次心跳。");
            healthView.setTextColor(C_GREEN);
        }
        if (boots > 0 || freezes > 0) {
            sb.append("\n进程启动 ").append(boots).append(" 次 · 疑似冻结 ").append(freezes).append(" 次");
        }
        healthView.setText(sb.toString());
    }

    private void loadStatus() {
        if (statusBusy) {
            return;
        }
        statusBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = Api.get(MainActivity.this, "/status");
                    lastStatusAt = System.currentTimeMillis();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            statusBusy = false;
                            lastStatus = o;
                            renderSummary(o);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            statusBusy = false;
                            accountSummary.setText("服务未响应：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void renderSummary(JSONObject o) {
        JSONArray arr = o.optJSONArray("accounts");
        int total = arr == null ? 0 : arr.length();
        int usable = 0;
        for (int i = 0; i < total; i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a != null && !a.optBoolean("disabled") && !a.optBoolean("cooling")) {
                usable++;
            }
        }
        if (total == 0) {
            accountSummary.setText("还没有账号。请到「账号」页登录或导入凭证。");
            accountSummary.setTextColor(C_RED);
        } else {
            accountSummary.setText("账号 " + total + " 个 · 可用 " + usable + " 个");
            accountSummary.setTextColor(usable > 0 ? C_GREEN : C_RED);
        }
    }

    // --------------------------------------------------------------- models

    private void loadModels() {
        modelsHint.setText("加载中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = Api.get(MainActivity.this, "/v1/models");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            renderModels(o);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            modelsHint.setText("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void renderModels(JSONObject o) {
        JSONArray arr = o.optJSONArray("data");
        modelsBox.removeAllViews();
        if (arr == null || arr.length() == 0) {
            modelsHint.setText("没有拿到模型列表。");
            return;
        }
        modelsHint.setText("共 " + arr.length() + " 个模型 · 倍率 x0.00 为免费，— 为上游未提供");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) {
                continue;
            }
            LinearLayout r = row();
            r.setPadding(0, dp(9), 0, dp(9));

            LinearLayout left = column();
            String id = m.optString("id", "");
            left.addView(text(id, 14, C_TEXT, true));
            long ctx = m.optLong("context_length", 0);
            long out = m.optLong("max_output_tokens", 0);
            String meta = "上下文 " + fmtK(ctx);
            if (out > 0) {
                meta += " · 输出 " + fmtK(out);
            }
            if (m.optBoolean("supports_images", false)) {
                meta += " · 可看图";
            }
            left.addView(text(meta, 11, C_MUTED, false));
            r.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String c = m.optString("credits", "");
            TextView badge = text(creditText(c), 12,
                    c.equals("x0.00") ? C_GREEN : (c.isEmpty() ? C_GRAY : C_BLUE), true);
            badge.setPadding(dp(9), dp(4), dp(9), dp(4));
            badge.setBackground(round(c.equals("x0.00") ? C_GREEN_SOFT :
                    (c.isEmpty() ? C_SURFACE : C_BLUE_SOFT), 20));
            r.addView(badge);
            modelsBox.addView(r);
            if (i < arr.length() - 1) {
                modelsBox.addView(divider());
            }
        }
    }

    private String creditText(String c) {
        if (c == null || c.isEmpty()) {
            return "—";
        }
        if (c.equals("x0.00")) {
            return "免费";
        }
        return c;
    }

    private String fmtK(long n) {
        if (n <= 0) {
            return "—";
        }
        if (n >= 1000) {
            return (n / 1000) + "k";
        }
        return String.valueOf(n);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    // -------------------------------------------------------------- credits

    private void loadCredits() {
        accountsBox.removeAllViews();
        accountsBox.addView(text("查询中…", 12, C_MUTED, false));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = Api.get(MainActivity.this, "/admin/credits");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            renderCredits(o);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            accountsBox.removeAllViews();
                            accountsBox.addView(text("查询失败：" + e.getMessage(), 12, C_RED, false));
                        }
                    });
                }
            }
        }).start();
    }

    private void renderCredits(JSONObject o) {
        JSONArray arr = o.optJSONArray("accounts");
        accountsBox.removeAllViews();
        if (arr == null || arr.length() == 0) {
            accountsBox.addView(text("还没有账号。先登录或导入凭证。", 12, C_MUTED, false));
            return;
        }
        Map<String, JSONObject> status = new HashMap<String, JSONObject>();
        if (lastStatus != null) {
            JSONArray sa = lastStatus.optJSONArray("accounts");
            for (int i = 0; sa != null && i < sa.length(); i++) {
                JSONObject s = sa.optJSONObject(i);
                if (s != null) {
                    status.put(s.optString("uid"), s);
                }
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String uid = a.optString("uid");
            JSONObject st = status.get(uid);

            LinearLayout c = column();
            c.setPadding(0, dp(10), 0, dp(12));

            LinearLayout head = row();
            String nick = a.optString("nickname", "");
            head.addView(text(nick.isEmpty() ? shortUid(uid) : nick, 14, C_TEXT, true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView pill = text(accountState(st), 11, 0xFFFFFFFF, true);
            pill.setPadding(dp(8), dp(3), dp(8), dp(3));
            pill.setBackground(round(stateColor(st), 20));
            head.addView(pill);
            c.addView(head);

            c.addView(text("UID " + uid, 10, C_GRAY, false));

            if (a.optBoolean("ok")) {
                long remain = a.optLong("remain");
                long size = a.optLong("size");
                long used = a.optLong("used");
                int pct = size > 0 ? (int) Math.max(0, Math.min(100, remain * 100 / size)) : 0;

                TextView cr = text("剩余 " + remain + " / " + size + " 积分", 13, C_TEXT, true);
                cr.setPadding(0, dp(6), 0, dp(4));
                c.addView(cr);

                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(pct);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
                pp.setMargins(0, 0, 0, dp(4));
                c.addView(pb, pp);

                c.addView(text("已用 " + used + " · " + pct + "%", 11, C_MUTED, false));
            } else {
                c.addView(text("积分查询失败：" + a.optString("error"),
                        11, C_RED, false));
            }

            if (st != null) {
                boolean cooling = st.optBoolean("cooling");
                boolean disabled = st.optBoolean("disabled");
                String reason = st.optString("reason", "");
                if (disabled) {
                    c.addView(text("已禁用" + (reason.isEmpty() ? "" : "：" + reason),
                            11, C_RED, false));
                } else if (cooling) {
                    c.addView(text("冷却中" + (reason.isEmpty() ? "" : "：" + reason),
                            11, C_ORANGE, false));
                }
            }
            accountsBox.addView(c);
            if (i < arr.length() - 1) {
                accountsBox.addView(divider());
            }
        }
    }

    private String accountState(JSONObject st) {
        if (st == null) {
            return "未知";
        }
        if (st.optBoolean("disabled")) {
            return "已禁用";
        }
        if (st.optBoolean("cooling")) {
            return "冷却中";
        }
        return "可用";
    }

    private int stateColor(JSONObject st) {
        if (st == null) {
            return C_GRAY;
        }
        if (st.optBoolean("disabled")) {
            return C_RED;
        }
        if (st.optBoolean("cooling")) {
            return C_ORANGE;
        }
        return C_GREEN;
    }

    private String shortUid(String uid) {
        if (uid == null || uid.isEmpty()) {
            return "账号";
        }
        return uid.length() <= 8 ? uid : uid.substring(0, 8);
    }

    private void doCheckin() {
        toast("正在签到…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = Api.post(MainActivity.this, "/admin/checkin", "{}");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            JSONArray res = o.optJSONArray("results");
                            int ok = 0, already = 0, fail = 0, disabled = 0;
                            StringBuilder detail = new StringBuilder();
                            for (int i = 0; res != null && i < res.length(); i++) {
                                JSONObject r = res.optJSONObject(i);
                                if (r == null) {
                                    continue;
                                }
                                String s = r.optString("status");
                                if ("OK".equals(s)) {
                                    ok++;
                                } else if ("ALREADY".equals(s)) {
                                    already++;
                                } else if ("DISABLED".equals(s)) {
                                    disabled++;
                                } else {
                                    fail++;
                                }
                                detail.append(shortUid(r.optString("uid")))
                                        .append("  ").append(s);
                                if (!r.optString("detail").isEmpty()) {
                                    detail.append(" · ").append(r.optString("detail"));
                                }
                                detail.append('\n');
                            }
                            show("签到结果",
                                    "成功 " + ok + " · 已签到 " + already
                                            + " · 失败 " + fail + " · 禁用 " + disabled
                                            + "\n\n" + detail);
                            loadStatus();
                            loadCredits();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            show("签到失败", e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置账号状态")
                .setMessage("会清除本地记录的「冷却 / 禁用 / 积分缓存」，不会删除凭证。\n"
                        + "如果账号本身没额度或登录态已失效，重置后仍会再次被标记。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        doReset();
                    }
                })
                .show();
    }

    private void doReset() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Api.post(MainActivity.this, "/admin/reset", "{}");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("已清除冷却/禁用标记");
                            loadStatus();
                            loadCredits();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            show("重置失败", e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- tasks

    private void loadTasks() {
        taskSummary.setText("加载中…");
        tasksBox.removeAllViews();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = Api.get(MainActivity.this, "/admin/tasks");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            renderTasks(o);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            taskSummary.setText("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void renderTasks(JSONObject o) {
        JSONObject sum = o.optJSONObject("summary");
        long energy = sum == null ? 0 : sum.optLong("energy");
        long streak = sum == null ? 0 : sum.optLong("streak_days");
        taskSummary.setText("能量 " + energy + " · 连续签到 " + streak + " 天");

        lastTasks = o.optJSONArray("tasks");
        tasksBox.removeAllViews();
        if (lastTasks == null || lastTasks.length() == 0) {
            tasksBox.addView(text("今天没有可展示的任务。", 12, C_MUTED, false));
            return;
        }
        for (int i = 0; i < lastTasks.length(); i++) {
            final JSONObject t = lastTasks.optJSONObject(i);
            if (t == null) {
                continue;
            }
            LinearLayout r = row();
            r.setPadding(0, dp(10), 0, dp(10));

            LinearLayout left = column();
            left.addView(text(t.optString("name"), 14, C_TEXT, true));
            String desc = t.optString("description");
            if (!desc.isEmpty()) {
                TextView d = text(desc, 11, C_MUTED, false);
                d.setMaxLines(2);
                left.addView(d);
            }
            long cur = t.optLong("current");
            long target = t.optLong("target");
            long reward = t.optLong("reward_credit");
            StringBuilder meta = new StringBuilder();
            meta.append(cur).append("/").append(target);
            if (reward > 0) {
                meta.append(" · +").append(reward).append(" 积分");
            }
            String status = t.optString("status");
            if (!status.isEmpty()) {
                meta.append(" · ").append(statusText(status));
            }
            left.addView(text(meta.toString(), 11,
                    cur >= target ? C_GREEN : C_MUTED, false));
            r.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (cur >= target) {
                final String code = t.optString("code");
                r.addView(button("领取", true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        claim(code);
                    }
                }));
            }
            tasksBox.addView(r);
            if (i < lastTasks.length() - 1) {
                tasksBox.addView(divider());
            }
        }
    }

    private String statusText(String s) {
        if ("not_accepted".equals(s)) {
            return "未接取";
        }
        if ("accepted".equals(s)) {
            return "进行中";
        }
        if ("completed".equals(s)) {
            return "已完成";
        }
        if ("claimed".equals(s)) {
            return "已领取";
        }
        return s;
    }

    private void claim(final String code) {
        toast("领取中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = Api.post(MainActivity.this, "/admin/tasks/claim",
                            "{\"task_code\":\"" + code + "\"}");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("领取成功 +" + o.optLong("credit") + " 积分");
                            loadTasks();
                            loadCredits();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            show("领取失败", e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void claimAll() {
        final List<String> codes = new ArrayList<String>();
        for (int i = 0; i < lastTasks.length(); i++) {
            JSONObject t = lastTasks.optJSONObject(i);
            if (t != null && t.optLong("current") >= t.optLong("target")) {
                codes.add(t.optString("code"));
            }
        }
        if (codes.isEmpty()) {
            toast("没有已完成、可领取的任务");
            return;
        }
        toast("正在领取 " + codes.size() + " 个任务…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0, credit = 0;
                StringBuilder failMsg = new StringBuilder();
                for (String code : codes) {
                    try {
                        JSONObject o = Api.post(MainActivity.this, "/admin/tasks/claim",
                                "{\"task_code\":\"" + code + "\"}");
                        ok++;
                        credit += o.optLong("credit");
                    } catch (Exception e) {
                        failMsg.append(code).append(": ").append(e.getMessage()).append('\n');
                    }
                }
                final int fok = ok;
                final int fcredit = credit;
                final String fmsg = failMsg.toString();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        show("一键领取", "成功 " + fok + " 个 · +" + fcredit + " 积分\n"
                                + (fmsg.isEmpty() ? "" : "\n失败：\n" + fmsg));
                        loadTasks();
                        loadCredits();
                    }
                });
            }
        }).start();
    }

    // --------------------------------------------------------- login/import

    private String webUrl() {
        boolean lan = Gateway.isLanEnabled(this);
        String ip = localIp();
        if (lan && ip != null) {
            return "http://" + ip + ":7863/";
        }
        return "http://127.0.0.1:7863/";
    }

    private void refreshLan() {
        boolean on = Gateway.isLanEnabled(this);
        String ip = localIp();
        if (on && ip != null) {
            headerSub.setText("局域网 · " + ip + ":7863");
            lanStatus.setText("已开启 · 同一 WiFi 下可访问，必须保管好 API Key");
            lanStatus.setTextColor(C_GREEN);
            lanAddr.setText("http://" + ip + ":7863/v1");
            if (webUrlView != null) {
                webUrlView.setText(webUrl());
            }
        } else if (on) {
            headerSub.setText("局域网 · 0.0.0.0:7863");
            lanStatus.setText("已开启，但没有检测到 WiFi/有线地址");
            lanStatus.setTextColor(C_ORANGE);
            lanAddr.setText("");
        } else {
            headerSub.setText("本机专属 · 127.0.0.1:7863");
            lanStatus.setText("未开启 · 只有本机应用能访问");
            lanStatus.setTextColor(C_MUTED);
            lanAddr.setText("");
            if (webUrlView != null) {
                webUrlView.setText(webUrl());
            }
        }
    }

    private void toggleLan() {
        boolean on = Gateway.isLanEnabled(this);
        try {
            Gateway.setLanEnabled(this, !on);
            restartIfRunning();
            if (!WbService.isRunning()) {
                startGateway();
            }
            refreshLan();
            toast(on ? "已关闭局域网访问" : "已开启，注意同一网络下不要泄露 Key");
        } catch (Exception e) {
            show("切换失败", String.valueOf(e.getMessage()));
        }
    }

    private String localIp() {
        try {
            java.util.List<java.net.NetworkInterface> all =
                    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces());
            for (java.net.NetworkInterface ni : all) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("dummy")) {
                    continue;
                }
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (ip != null && !ip.startsWith("169.254.")) {
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void chooseLoginRegion() {
        final String[] items = {"国内版（codebuddy.cn）", "国际版（workbuddy.ai）"};
        String cur = Gateway.getLoginRegion(this);
        new AlertDialog.Builder(this)
                .setTitle("选择账号区域（当前：" + ("global".equals(cur) ? "国际版" : "国内版") + "）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String r = which == 0 ? "cn" : "global";
                        try {
                            Gateway.setLoginRegion(MainActivity.this, r);
                        } catch (Exception ignored) {
                        }
                        beginLogin();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void beginLogin() {
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String out = Gateway.runLogin(MainActivity.this,
                            "url", "--region", Gateway.getLoginRegion(MainActivity.this));
                    final String url = Gateway.firstHttpUrl(out);
                    if (url == null) {
                        throw new Exception(out.trim());
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            copySilently(url);
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            } catch (Exception e) {
                                toast("无法打开浏览器，授权链接已复制");
                            }
                            show("登录进行中",
                                    "已在浏览器打开授权页。完成登录后回到本页点『完成登录』。\n\n"
                                            + "浏览器没打开的话，链接已复制到剪贴板。");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            show("获取授权链接失败", String.valueOf(e.getMessage()));
                        }
                    });
                }
            }
        }).start();
    }

    private void finishLogin() {
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String out = Gateway.runLogin(MainActivity.this,
                            "poll", "--out", Gateway.authDir(MainActivity.this).getAbsolutePath(),
                            "--region", Gateway.getLoginRegion(MainActivity.this));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            restartIfRunning();
                            toast("登录成功，凭证已保存");
                            show("登录完成", out.trim());
                            loadStatus();
                            loadCredits();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            show("登录未完成", String.valueOf(e.getMessage()));
                        }
                    });
                }
            }
        }).start();
    }

    private void pickAuthFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            show("无法打开文件选择器", String.valueOf(e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_AUDIO) {
            classifyPickedAudio(data.getData());
            return;
        }
        if (requestCode != REQ_IMPORT) {
            return;
        }
        Uri uri = data.getData();
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String json = bos.toString("UTF-8");
            if (!json.contains("accessToken")) {
                show("导入失败", "文件里没有 accessToken，可能不是 WorkBuddy 凭证。");
                return;
            }
            File out = new File(Gateway.authDir(this),
                    "workbuddy-import-" + System.currentTimeMillis() + ".json");
            Gateway.write(out, json.getBytes("UTF-8"));
            restartIfRunning();
            toast("已导入 " + out.getName());
            loadStatus();
            loadCredits();
        } catch (Exception e) {
            show("导入失败", String.valueOf(e.getMessage()));
        }
    }

    private void restartIfRunning() {
        if (WbService.isRunning()) {
            startService(new Intent(this, WbService.class).setAction(WbService.ACTION_RESTART));
        }
    }

    // ---------------------------------------------------------- diagnostics

    /** 内置 3 秒测试视频（360x240，H.264 + AAC），不依赖外网。 */
    private static final String TEST_VIDEO_ASSET = "test.mp4";
    private static final String TEST_MODEL = "step-5-preview";

    private Button productButton(String label, final String value) {
        return button(label, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchProduct(value);
            }
        });
    }

    private String productLabel(String p) {
        if ("workbuddy".equals(p)) {
            return "WorkBuddy 桌面端";
        }
        if ("vscode".equals(p)) {
            return "VS Code 插件";
        }
        return "CLI（命令行）";
    }

    private void switchProduct(final String value) {
        if (value.equals(Gateway.getProduct(this))) {
            toast("已经是这个身份");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("切换上游身份")
                .setMessage("将切换到「" + productLabel(value) + "」，服务会重启（凭证和状态保留）。\n"
                        + "切换后再点『视频通路测试』，对比不同身份的结果。")
                .setNegativeButton("取消", null)
                .setPositiveButton("切换", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            Gateway.setProduct(MainActivity.this, value);
                            restartIfRunning();
                            if (!WbService.isRunning()) {
                                startGateway();
                            }
                            productView.setText("当前上游身份：" + productLabel(value));
                            toast("已切换，服务重启中");
                        } catch (Exception e) {
                            show("切换失败", String.valueOf(e.getMessage()));
                        }
                    }
                })
                .show();
    }

    private String audioLabel(String mode) {
        if ("hybrid".equals(mode)) {
            return "ASR 转写 + 本地音效识别（YAMNet 521 类）";
        }
        return "仅 ASR 转文字（不识别音效）";
    }

    private void switchAudioMode(final String mode) {
        if (mode.equals(Gateway.getAudioMode(this))) {
            toast("已经是这个模式");
            return;
        }
        try {
            Gateway.setAudioMode(this, mode);
            restartIfRunning();
            if (!WbService.isRunning()) {
                startGateway();
            }
            audioModeView.setText("音频处理：" + audioLabel(mode));
            toast("已切换，服务重启中");
        } catch (Exception e) {
            show("切换失败", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------- 视频抽帧（无极调节）

    private interface IntChange {
        void on(int v);
    }

    private static final class Slider {
        LinearLayout view;
        SeekBar bar;
        TextView value;
        int min;
        String unit;

        int get() {
            return min + bar.getProgress();
        }

        void setEnabled(boolean on) {
            bar.setEnabled(on);
            view.setAlpha(on ? 1f : 0.38f);
        }
    }

    private Slider slider(String name, final int min, final int max, int value,
                          final String unit, final IntChange cb) {
        final Slider s = new Slider();
        s.min = min;
        s.unit = unit;

        LinearLayout box = column();
        box.setPadding(0, dp(8), 0, 0);

        LinearLayout head = row();
        head.addView(text(name, 13, C_TEXT, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = text(value + unit, 13, C_BLUE, true);
        val.setTypeface(Typeface.MONOSPACE);
        head.addView(val);
        box.addView(head);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setProgressTintList(ColorStateList.valueOf(C_BLUE));
        bar.setThumbTintList(ColorStateList.valueOf(C_BLUE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(C_LINE));
        bar.setPadding(dp(4), dp(9), dp(4), dp(9));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                val.setText(v + unit);
                if (cb != null) {
                    cb.on(v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        s.view = box;
        s.bar = bar;
        s.value = val;
        return s;
    }

    /** 拖动时只更新预估，点「应用」才落盘并重启（三个滑块只重启一次）。 */
    private void refreshFrameEstimate() {
        if (frameEstimate == null || frameSizeSlider == null || frameCountSlider == null) {
            return;
        }
        boolean auto = frameAuto != null && frameAuto.isChecked();
        int n = auto ? 12 : frameCountSlider.get();
        int size = frameSizeSlider.get();
        int q = frameQualitySlider == null ? 80 : frameQualitySlider.get();
        double perFrame = size * (double) size * 0.12 * (q / 80.0);
        double total = perFrame * n;
        String cnt = auto ? "自适应（约 " + n + " 帧）" : n + " 帧";
        frameEstimate.setText(String.format(Locale.US,
                "%s · 每帧约 %d KB · 单次请求约 %.1f MB",
                cnt, Math.round(perFrame / 1024), total / 1048576));
    }

    private void applyFrameTuning() {
        if (frameCountSlider == null) {
            return;
        }
        try {
            int count = frameAuto != null && frameAuto.isChecked() ? -1 : frameCountSlider.get();
            Gateway.setFrameTuning(this, count, frameSizeSlider.get(),
                    frameQualitySlider == null ? 80 : frameQualitySlider.get());
            restartIfRunning();
            if (!WbService.isRunning()) {
                startGateway();
            }
            toast("抽帧参数已生效");
        } catch (Exception e) {
            show("保存失败", String.valueOf(e.getMessage()));
        }
    }

    /** 卡片内的分组标题 + 细分割线。 */
    private LinearLayout divider(String label) {
        LinearLayout box = column();
        box.setPadding(0, dp(12), 0, 0);
        box.addView(text(label, 13, C_TEXT, true));
        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.setMargins(0, dp(8), 0, dp(2));
        box.addView(line, lp);
        return box;
    }

    private String qualityLabel(String q) {
        return "fast".equals(q) ? "快速 · YAMNet（省电）" : "精细 · YAMNet 定位 + PANNs CNN14（多标签）";
    }

    private void switchQuality(final String q) {
        if (q.equals(Gateway.getAudioQuality(this))) {
            toast("已经是这个精度");
            return;
        }
        try {
            Gateway.setAudioQuality(this, q);
            restartIfRunning();
            if (!WbService.isRunning()) {
                startGateway();
            }
            audioQualityView.setText("音频精度：" + qualityLabel(q));
            toast("fast".equals(q) ? "已切到快速模式" : "已切到精细模式，首次分析会加载 160MB 模型");
        } catch (Exception e) {
            show("切换失败", String.valueOf(e.getMessage()));
        }
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, REQ_AUDIO);
        } catch (Exception e) {
            show("无法打开文件选择器", String.valueOf(e.getMessage()));
        }
    }

    private void classifyPickedAudio(final Uri uri) {
        toast("正在解码并分类，请稍候…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = null;
                try {
                    File dir = new File(Gateway.dir(MainActivity.this), "tmp");
                    dir.mkdirs();
                    tmp = new File(dir, "wbprobe-" + System.currentTimeMillis() + ".bin");
                    InputStream in = getContentResolver().openInputStream(uri);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                    in.close();
                    fos.close();

                    boolean fine = "fine".equals(Gateway.getAudioQuality(MainActivity.this));
                    String body = "{\"path\":" + JSONObject.quote(tmp.getAbsolutePath())
                            + ",\"max_seconds\":90,\"fine\":" + fine + "}";
                    String resp = postJsonLocal("http://127.0.0.1:" + FrameService.PORT + "/audio", body);
                    final JSONObject o = new JSONObject(resp);
                    final StringBuilder sb = new StringBuilder();
                    JSONArray events = o.optJSONArray("events");
                    if (events == null || events.length() == 0) {
                        sb.append("没有识别到确定的声音事件。\n可能音频过短、编码不支持，或环境太安静。");
                    } else {
                        sb.append("识别到 ").append(events.length()).append(" 段：\n\n");
                        for (int i = 0; i < events.length(); i++) {
                            JSONObject e = events.getJSONObject(i);
                            String detail = e.optString("zh", e.optString("label"));
                            String fam = familyName(e.optString("family", ""));
                            String name = fam == null ? detail
                                    : (fam.equals(detail) ? fam : fam + "（" + detail + "）");
                            if (e.optString("gender", "").equals("male")) {
                                name += " · 男";
                            } else if (e.optString("gender", "").equals("female")) {
                                name += " · 女";
                            }
                            sb.append(String.format("%s-%s  %s  %.2f\n",
                                    fmtClock(e.optDouble("start", 0)),
                                    fmtClock(e.optDouble("end", 0)),
                                    name,
                                    e.optDouble("score", 0)));
                        }
                    }
                    JSONObject music = o.optJSONObject("music");
                    if (music != null) {
                        sb.append("\n音乐画像：");
                        appendTags(sb, "风格", music.optJSONArray("genres"));
                        appendTags(sb, "乐器", music.optJSONArray("instruments"));
                        appendTags(sb, "人声", music.optJSONArray("vocals"));
                        appendTags(sb, "情绪", music.optJSONArray("moods"));
                        if (music.has("bpm")) {
                            sb.append("\n  节奏：").append(music.optInt("bpm")).append(" BPM");
                        }
                        if (music.optBoolean("instrumental")) {
                            sb.append("\n  纯音乐（未检出人声）");
                        }
                    }
                    JSONArray top = o.optJSONArray("top");
                    if (top != null && top.length() > 0) {
                        sb.append("\n最可能的类别：\n");
                        for (int i = 0; i < Math.min(5, top.length()); i++) {
                            JSONObject e = top.getJSONObject(i);
                            sb.append("  ").append(e.optString("zh", e.optString("label")))
                                    .append("  ").append(String.format("%.2f", e.optDouble("score", 0)))
                                    .append('\n');
                        }
                    }
                    sb.append("\n模型：").append(o.optString("model", "yamnet"))
                            .append(" · 总耗时 ").append(o.optInt("cost_ms", 0)).append(" ms")
                            .append("（解码 ").append(o.optInt("decode_ms", 0))
                            .append(" / YAMNet ").append(o.optInt("yamnet_ms", 0))
                            .append(" / PANNs ").append(o.optInt("panns_ms", 0)).append("）")
                            .append(" · ").append(o.optInt("frames", 0)).append(" 帧");
                    final String raw = o.toString(2);
                    final String summary = sb.toString();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showRaw("音频分类结果", summary, raw);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            show("音频分类失败", String.valueOf(e.getMessage())
                                    + "\n\n若是 " + FrameService.PORT + " 端口拒绝连接，先启动网关。");
                        }
                    });
                } finally {
                    if (tmp != null) {
                        tmp.delete();
                    }
                }
            }
        }).start();
    }

    /** 与 AudioTagger.familyZh 对应；未知家族返回 null。 */
    private static String familyName(String family) {
        if (family == null) {
            return null;
        }
        switch (family) {
            case "speech": return "说话";
            case "singing": return "唱歌";
            case "laugh": return "笑声";
            case "cry": return "哭泣";
            case "scream": return "叫喊";
            case "crowd": return "掌声/欢呼";
            case "breath": return "喘息";
            case "impact": return "撞击声";
            case "music": return "音乐";
            case "instrument": return "乐器声";
            case "vehicle": return "车辆声";
            case "alarm": return "警报声";
            case "nature": return "自然声";
            case "footsteps": return "脚步声";
            case "household": return "家居声";
            case "animal": return "动物声";
            default: return null;
        }
    }

    private static void appendTags(StringBuilder sb, String name, JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return;
        }
        sb.append("\n  ").append(name).append("：");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) {
                continue;
            }
            if (i > 0) {
                sb.append("、");
            }
            sb.append(t.optString("zh", t.optString("label")));
            sb.append(String.format(" %.2f", t.optDouble("score", 0)));
        }
    }

    private static String fmtClock(double sec) {
        int s = (int) Math.max(0, sec);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static String postJsonLocal(String url, String body) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        java.io.OutputStream os = conn.getOutputStream();
        try {
            os.write(body.getBytes("UTF-8"));
        } finally {
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        if (in != null) {
            in.close();
        }
        conn.disconnect();
        String text = bos.toString("UTF-8");
        if (code >= 400) {
            throw new java.io.IOException("HTTP " + code + ": " + text);
        }
        return text;
    }

    private void testVideo() {
        if (!WbService.isRunning()) {
            show("服务未启动", "先点「启动网关」，再测视频通路。");
            return;
        }
        toast("测试中：先跑本地管线，再问模型，最长约 3 分钟…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String dataUrl = assetDataUrl(TEST_VIDEO_ASSET);
                    final Api.Raw p = Api.postRaw(MainActivity.this, "/admin/video-probe",
                            "{\"video\":" + JSONObject.quote(dataUrl) + "}", 240000);
                    boolean ok = false;
                    String probeLine;
                    if (p.code != 200) {
                        probeLine = "本地管线：HTTP " + p.code + "\n" + p.body;
                    } else {
                        JSONObject st = new JSONObject(p.body);
                        int frames = st.optInt("frames");
                        ok = frames > 0 && st.optInt("failed") == 0;
                        probeLine = "本地管线：" + (ok ? "成功" : "失败")
                                + "\n  来源 " + st.optString("source")
                                + " · 视频 " + fmtBytes(st.optLong("bytes"))
                                + " · 帧数 " + frames
                                + " · 声音事件 " + st.optInt("audio_events")
                                + " · 台词 " + st.optInt("transcript_chars") + " 字"
                                + " · 耗时 " + st.optInt("elapsed_ms") + " ms";
                        if (!ok) {
                            probeLine += "\n  原因：" + st.optString("reason", "未知");
                        }
                        if (st.optInt("audio_events") > 0) {
                            probeLine += "\n  声音：" + st.optString("audio_summary", "");
                        }
                    }
                    if (!ok) {
                        final String verdict = probeLine
                                + "\n\n本地这一步就没成功，所以没有再发给模型——"
                                + "否则上游会过滤掉视频、模型只会回「不支持视频」，容易误判。";
                        final String raw = p.body;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                showRaw("视频通路测试 · 本地管线", verdict, raw);
                            }
                        });
                        return;
                    }

                    final Api.Raw r = Api.postRaw(MainActivity.this, "/v1/chat/completions",
                            videoTestBody(dataUrl), 300000);
                    final String verdict = probeLine + "\n\n" + judgeVideo(r);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showRaw("视频通路测试 · " + productLabel(Gateway.getProduct(MainActivity.this)),
                                    verdict, r.body);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            show("测试失败", String.valueOf(e.getMessage()));
                        }
                    });
                }
            }
        }).start();
    }

    /** 读取 assets 里的视频并转成 data URL，完全离线，不依赖外网链接。 */
    private String assetDataUrl(String name) throws Exception {
        InputStream in = getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return "data:video/mp4;base64,"
                + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static String videoTestBody(String dataUrl) {
        return "{\"model\":" + JSONObject.quote(TEST_MODEL) + ",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"video_url\",\"video_url\":{\"url\":" + JSONObject.quote(dataUrl) + "}},"
                + "{\"type\":\"text\",\"text\":\"这是网关内置的 3 秒测试视频，画面是彩色的测试图案，"
                + "声音是单一的正弦音。请用一两句话描述你看到的画面和听到的声音。\"}]}]}";
    }

    private static String fmtBytes(long n) {
        if (n <= 0) {
            return "0 B";
        }
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
    }

    /** 先用 prompt_tokens 区分「图片没发出去」和「模型不吃图」，再给结论。 */
    private String judgeVideo(Api.Raw r) {
        int pt = 0;
        String content = "";
        String reasoning = "";
        try {
            JSONObject o = new JSONObject(r.body);
            JSONObject u = o.optJSONObject("usage");
            if (u != null) {
                pt = u.optInt("prompt_tokens");
            }
            JSONArray ch = o.optJSONArray("choices");
            if (ch != null && ch.length() > 0) {
                JSONObject msg = ch.optJSONObject(0).optJSONObject("message");
                if (msg != null) {
                    content = msg.optString("content", "");
                    reasoning = msg.optString("reasoning_content", "");
                }
            }
        } catch (Exception ignored) {
        }
        String both = content + " " + reasoning;
        boolean denied = both.contains("不支持") || both.contains("无法查看") || both.contains("无法处理视频")
                || both.contains("无法直接") || both.contains("cannot") || both.contains("unable");

        if (r.code == 200) {
            if (denied) {
                if (pt > 0 && pt < 300) {
                    return "结论：模型说看不到视频，且 prompt_tokens=" + pt + "（只有纯文本）→ "
                            + "图片没进入上游请求，问题在网关侧（抽帧或改写）。看上面的管线数字。";
                }
                return "结论：模型说看不到视频，但 prompt_tokens=" + pt + " → "
                        + "图片确实发出去了，是模型/上游过滤了图像输入，换「可看图」的模型再试。";
            }
            if (!content.isEmpty()) {
                return "结论：成功，模型描述了视频内容（prompt_tokens=" + pt + "）。";
            }
            return "HTTP 200，但 content 为空（可能只有 reasoning）。prompt_tokens=" + pt + "。";
        }
        if (r.code == 400) {
            return "HTTP 400：上游拒绝了请求。如果是 invalid content，说明 content 里仍有上游不认的 part。";
        }
        if (r.code == 413) {
            return "HTTP 413：请求体超过 24MB，降低抽帧档位或改用公网链接。";
        }
        if (r.code == 401) {
            return "HTTP 401：API Key 不对。";
        }
        if (r.code == 503) {
            return "HTTP 503：没有可用账号，先看「账号」页。";
        }
        return "HTTP " + r.code + "，看下面的原始响应。";
    }

    /** 开源组件与许可清单。 */
    private void showOss() {
        String text = "本应用是个人技术研究项目，与腾讯 / WorkBuddy 官方无关。\n\n"
                + "── 协议与代理 ──\n"
                + "workbuddy2api\n"
                + "  上游协议实现参考：github.com/Vita0519/workbuddy2api\n"
                + "  （fork 自 Sliverkiss/workbuddy2api，原仓库已不可访问）\n"
                + "  上游未附带 LICENSE 文件；本应用在其基础上做了大量修改与重构。\n\n"
                + "── 推理运行时 ──\n"
                + "TensorFlow Lite / LiteRT\n"
                + "  Apache License 2.0\n"
                + "  Copyright 2017 The TensorFlow Authors\n"
                + "  https://www.tensorflow.org/lite\n\n"
                + "── 音频模型 ──\n"
                + "YAMNet（音频事件分类）\n"
                + "  Apache License 2.0\n"
                + "  Copyright 2019 The TensorFlow Authors\n"
                + "  模型：google/lite-model/yamnet（TF Hub）\n"
                + "  AudioSet 类别表：CC BY 4.0（Google Research）\n\n"
                + "PANNs CNN14（音乐 / 环境声分类）\n"
                + "  CC BY 4.0\n"
                + "  Qiuqiang Kong, Yin Cao, Turab Iqbal, Yuxuan Wang,\n"
                + "  Wenwu Wang, Mark D. Plumbley.\n"
                + "  \"PANNs: Large-Scale Pretrained Audio Neural Networks\n"
                + "  for Audio Pattern Recognition\"\n"
                + "  https://github.com/qiuqiangkong/audioset_tagging_cnn\n\n"
                + "── 其他 ──\n"
                + "Android SDK — Apache License 2.0\n"
                + "内置测试视频由 FFmpeg 生成（测试图案 + 正弦音）\n"
                + "FFmpeg — LGPL 2.1+ / GPL（仅用于构建期生成素材）\n\n"
                + "WorkBuddy、CodeBuddy 等名称与商标归腾讯所有；\n"
                + "本应用不包含其客户端代码。";
        TextView t = text(text, 11, C_TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("开源组件与许可")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showRaw(String title, String summary, String body) {
        String text = summary + "\n\n------ 原始响应（前 4000 字）------\n"
                + (body == null ? "" : (body.length() > 4000 ? body.substring(0, 4000) : body));
        TextView t = text(text, 10, C_TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        copySilently(text);
                        toast("已复制");
                    }
                })
                .show();
    }

    // --------------------------------------------------------------- misc

    private void setBusy(boolean busy) {
        loginBtn.setEnabled(!busy);
        doneBtn.setEnabled(!busy);
        importBtn.setEnabled(!busy);
    }

    private void copy(String text) {
        copySilently(text);
        toast("已复制");
    }

    private void copySilently(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("workbuddy", text));
        }
    }

    private void showLogDialog() {
        TextView t = text(WbService.logText().length() == 0 ? "暂无日志" : WbService.logText(),
                10, C_TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void show(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null ? "" : message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }
}
