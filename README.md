# WorkBuddy 网关

<p align="center">
  <b>把 WorkBuddy 额度，变成手机上随开随用的 OpenAI 兼容接口</b><br>
  不用电脑 · 不用 root · 装好即用
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/API-OpenAI%20Compatible-10a37f" alt="OpenAI Compatible">
  <img src="https://img.shields.io/badge/Go-1.22-00ADD8?logo=go&logoColor=white" alt="Go">
  <img src="https://img.shields.io/badge/License-Research%20Only-lightgrey" alt="License">
  <img src="https://img.shields.io/badge/Release-v1.0-blue" alt="Release">
</p>

---

## 简介

WorkBuddy 网关是一个 **Android 本地网关**：把 WorkBuddy（CodeBuddy）账号额度包装成标准 OpenAI 兼容接口，在手机上本地运行。任何支持自定义 API 地址的客户端——Cherry Studio、NextChat、lobe-chat、各种 AI 聊天工具——填上地址就能用。

- **接口**：`http://127.0.0.1:7863/v1`（局域网开启后 `http://<手机IP>:7863/v1`）
- **包名**：`io.local.wbgateway`
- **系统**：Android 8.0+（API 26），arm64-v8a / armeabi-v7a
- **形态**：完整版 170 MB（含音频模型）/ 轻量版 12 MB（仅转写）

> **需要自备 WorkBuddy 账号**。本项目仅供技术研究，非官方客户端接入可能违反服务条款，账号风险自负。

## 功能一览

| 类别 | 能力 |
|---|---|
| **账号** | 多账号智能轮转（按积分选号）、国内 / 国际版登录、冷却与自动解冻、一键签到、成长任务领取 |
| **模型** | 模型列表、上下文 / 最大输出、积分倍率（`x0.00` 标免费）、可看图标记 |
| **多模态** | 图片原样透传；视频自动抽帧（按声音事件取帧、逐帧带时间戳）；音频转写 + 声音事件标注 + 音乐画像 |
| **声音标注** | 15 类声音家族 SED，输出形如 `0:12-0:15 说话（女）`、`0:20-0:22 撞击声`；转写与事件同条输出 |
| **共享** | 局域网一键开关、内置网页聊天客户端（浏览器直聊） |
| **界面** | 四页签（概览 / 模型 / 账号 / 任务）、横屏两列布局、状态栏导航栏沉浸、内置诊断与开源许可页 |
| **省电** | 无唤醒锁，仅前台服务 |

## 重点能力

### 视频自动分帧

WorkBuddy 的上游通道会过滤原生视频，所以网关在本地把视频「拆」成视觉模型能看懂的形式：

```
视频 URL / base64
   ↓ 下载到本地临时目录（不写相册）
Android 本地服务（127.0.0.1:7864）
   ├─ ① 先解音轨 → YAMNet 做声音事件分析
   ├─ ② 按「事件点 + 均匀点」抽帧（MediaMetadataRetriever）
   │     有声音事件的时刻优先取帧，剩余预算均匀铺开
   │     每帧带时间戳，分辨率按档位（640 / 768 / 1024px）
   └─ ③ 音轨转 16kHz WAV → WorkBuddy ASR 转写台词
   ↓
改写请求：时间表 + 声音事件 + 台词 + 8~16 张带时间戳的图
   ↓
上游视觉模型（图片走标准 image_url，对客户端透明）
```

| 特性 | 说明 |
|---|---|
| **事件引导抽帧** | 不是死板地均匀切——说话、撞击、掌声发生的时刻优先取帧，画面和声音对得上 |
| **无极调节** | 帧数 4–24（或按声音事件自适应）、单帧 480–1280px、JPEG 质量 50–95 三根滑杆，实时显示单帧体积与单次请求总量 |
| **台词同步** | 视频原声自动转写，「谁说了什么」和「画面有什么」同一份上下文给模型 |
| **失败不撒谎** | 抽帧失败会替换成明确的失败原因，不会伪装成「模型不支持视频」 |
| **离线诊断** | 内置 3 秒测试视频，「视频通路测试」两步定位：先本地管线、再发模型 |

> 实测提示：动作理解靠帧间推断，打斗这类连续动作别期待太高；场景、人数、字幕、台词、环境音都很稳。

### 音频识别

音频不是简单的「语音转文字」——转写、声音事件、人物属性、音乐画像四层一起输出，而且**同条时间轴**。

```
音频 / 视频音轨
   ├─ WorkBuddy ASR（/agenttool/v1/asr）→ 转写文本 + 时间戳
   ├─ YAMNet 521 类（4 MB，帧级）→ 15 类声音家族事件
   └─ PANNs CNN14 527 类（155 MB，10 秒窗）→ 补充标签 + 音乐画像
   ↓
拼成一段交给模型
```

**15 类声音家族**：说话 / 唱歌 / 笑声 / 哭泣 / 叫喊 / 掌声 / 喘息 / 撞击 / 音乐 / 乐器 / 车辆 / 警报 / 自然 / 脚步 / 家居 / 动物。允许重叠——说话 + 背景音乐 + 笑声可以同时报。

输出长这样：

```
转写内容（自动转写，可能有误）：
0:03-0:05 你昨天去哪了
0:06-0:08 我去了一趟医院

声音事件（本地分类，按时间轴）：
0:00-0:12 说话（女）
0:03-0:04 笑声
0:12-0:15 音乐（流行 / 钢琴 / 女声演唱）
```

| 技术点 | 做法 |
|---|---|
| **家族 SED** | 15 个家族各自做双阈值滞回检测，副语言（笑/喘/叫/哭）用 top1+0.5×top2 恢复被摊薄的分数 |
| **性别 / 音高** | 自相关估基频（60–500 Hz），输出 `说话（女）`、`尖叫（高音）`；尖叫不标性别——男女尖叫基频重叠，硬标就是编 |
| **音乐画像** | 风格 top3、乐器 top3、人声类型、情绪、BPM，不再只说「音乐」 |
| **噪声抑制** | 正弦音、白噪声、静音占主导时压掉其他事件，测试音不会被标成「电话铃声」 |
| **两档精度** | 快速（YAMNet）/ 精细（PANNs），模式与精度独立切换 |

> 轻量版只有转写层（走 ASR），没有本地声音事件模型——检测不到模型就自动隐藏入口，不会产生无效请求。

## 更新日志

### v1.1

| 项目 | 说明 |
|---|---|
| **抽帧无极调节** | 概览页一张卡片显示当前参数，点开是底部弹层（Material bottom sheet）：帧数 4–24（可切「按声音事件自适应」）、分辨率 480–1280、JPEG 质量 50–95，实时算出每帧 KB 与单次请求 MB，点「应用」才落盘重启 |
| **概览页重排** | 接入信息 → 视频抽帧 → 运行健康 → 诊断（默认收起）→ 日志；诊断不再摊开占两屏，参数也不用滚到底 |
| **会话粘性** | 同一段对话绑定同一账号（TTL 30 分钟、滑动续期、上限 1024 条），多轮不再因余额变化跳号，上游前缀缓存能留住；账号失败自动解绑 |
| **分错误码冷却** | 402 余额不足关到次日 04:00（积分重置点）而非固定 12h；429 从 10 分钟起指数退避、1 小时封顶；404 固定 60s 不参与退避 |
| **运行健康** | 服务每 30s 心跳，界面显示「进程启动 N 次 / 疑似冻结 N 次」；心跳超过 90s 没更新直接提示可能被系统冻结，并指向电池白名单 |
| **界面重做** | 渐变头部、分段式页签、卡片 20dp 圆角 + 1dp 投影、等宽数字、统一色板（#2F6BFF 主色）；日志区等宽字体，倍率与积分不再歪斜 |

### v1.0

首个公开版本。

## 下载

到 [Releases](../../releases) 页面下载最新 APK：

| 包 | 体积 | 音频能力 |
|---|---|---|
| `WorkBuddyGateway-v1.0.apk` | 170 MB | 转写 + 声音事件 + 音乐画像（内置 YAMNet + PANNs） |
| `WorkBuddyGateway-v1.0-lite.apk` | 12 MB | 仅语音转写 |

两个包同包名同签名，**只能装一个**，互相覆盖安装会保留账号和配置。轻量版不是残血版——只是不带音频模型，检测不到就自动隐藏相关入口，服务强制走转写，不会产生无效请求。

## 快速上手

1. 下载 APK，允许「未知来源」安装；
2. 首次打开授予**通知权限**（前台服务需要）；
3. 「登录账号」走浏览器授权，回来点「完成登录」；也可以导入电脑上的 `workbuddy-*.json` 凭证；
4. 概览页复制 **Base URL** 和 **API Key**，填进任意 OpenAI 兼容客户端；
5. 要给电脑 / 平板用，打开「局域网访问」，填 `http://<手机IP>:7863/v1`。

不想装客户端？浏览器打开 `http://<手机IP>:7863/` 用内置网页版直接聊。

## 目录结构

```
WorkBuddyGateway/
├── gateway/                 # Go 网关核心（NDK 交叉编译成 .so 内嵌运行）
│   ├── cmd/                 # server · login · credit · signin
│   ├── internal/            # auth · pool · upstream · server · scheduler
│   │   └── server/web/      # 内置网页聊天客户端（go:embed）
│   ├── go.mod
│   └── config.example.json
├── app/                     # Android 壳（Java）
│   ├── MainActivity.java    # 四页签界面 · 诊断 · 开源许可
│   ├── WbService.java       # 前台服务（拉起网关子进程）
│   ├── FrameService.java    # 本地 7864 服务：抽帧 + 音轨
│   ├── AudioTagger.java     # 声音家族 SED · 时间轴
│   ├── PannsModel.java      # PANNs CNN14 推理
│   ├── MusicProfile.java    # 风格 / 乐器 / BPM
│   ├── PitchTempo.java      # 基频 · 性别 · 速度
│   ├── AndroidManifest.xml  # 完整版清单
│   ├── AndroidManifest-lite.xml
│   ├── libs/tflite-all.jar
│   ├── models/              # 随包资源（yamnet / mel / 标签）
│   └── res/drawable/
├── build/
│   ├── build.sh             # 打包脚本（完整版 / 轻量版）
│   └── test.mp4             # 诊断用 3 秒离线视频
└── docs/USAGE.md            # 完整使用手册
```

## 构建

### 环境

- Go 1.22+、Android NDK r26+（交叉编译 `GOOS=android CGO_ENABLED=1`）
- Android SDK build-tools 34+（aapt2 / d8 / zipalign / apksigner）
- JDK 17

### 构建 Go 网关

```bash
cd gateway
# arm64
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
  go build -o ../app/jniLibs/arm64-v8a/libwb2api.so ./cmd/server
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
  go build -o ../app/jniLibs/arm64-v8a/libwb2login.so ./cmd/login
# armeabi-v7a 同理（armv7-linux-androideabi26-clang）
```

> 以 `lib*.so` 命名放入 jniLibs 是为了绕过 targetSdk 28+ 对可执行文件的限制，App 通过 `System.loadLibrary` 加载后以子进程方式运行。

### 打包 APK

```bash
cd build
./build.sh          # 完整版（含模型）
./build.sh lite     # 轻量版（无模型，应用名「轻量版」）
```

脚本流程：aapt2 编译资源 → d8 转 dex → 合并 jniLibs / assets → zipalign → apksigner（v2+v3）签名。

### 模型文件

仓库只带小模型，`app/models/` 里已有：

| 文件 | 体积 | 来源 | 许可 |
|---|---|---|---|
| `yamnet.tflite` | 4 MB | TF Hub `google/lite-model/yamnet/tflite/1` | Apache 2.0 |
| `mel_basis.bin` | 132 KB | log-mel 滤波器组（与 PANNs 配套） | — |
| `yamnet_labels.txt` | — | AudioSet 521 类 | CC BY 4.0 |
| `panns_labels.txt` | — | PANNs / AudioSet 527 类 | CC BY 4.0 |

**`cnn14.tflite`（155 MB）不在仓库里**——超过 GitHub 单文件上限，需要自己转：

```bash
# 1. 拿官方权重
pip install panns-inference
python -c "from panns_inference import AudioTagging; at=AudioTagging(checkpoint_path=None, device='cpu')"
# 2. 导出 ONNX 后用 onnx2tf 转 TFLite（输入固定 10 秒 @ 32 kHz log-mel）
# 3. 放进 app/models/cnn14.tflite，重新打包
```

没有这个文件也能跑：`Gateway.hasAudioModel()` 检测不到就自动降级为轻量版行为——隐藏声音事件与音乐画像入口，服务强制 `audio_mode=asr`。

## 使用说明

完整使用文档见 [docs/USAGE.md](docs/USAGE.md)（页面说明、视频 / 音频通路、诊断排查、已知限制、Termux 版对比）。

## 开源组件与许可

| 组件 | 许可证 |
|---|---|
| [TensorFlow Lite / LiteRT](https://www.tensorflow.org/lite) | Apache License 2.0 |
| [YAMNet](https://tfhub.dev/google/lite-model/yamnet/tflite/1) | Apache License 2.0 |
| AudioSet 类别表 | CC BY 4.0（Google Research） |
| [PANNs CNN14](https://github.com/qiuqiangkong/audioset_tagging_cnn) | CC BY 4.0 — Qiuqiang Kong et al., *PANNs: Large-Scale Pretrained Audio Neural Networks for Audio Pattern Recognition* |
| Android SDK | Apache License 2.0 |
| 协议实现参考 [workbuddy2api](https://github.com/Vita0519/workbuddy2api) | 上游未附 LICENSE；本项目在其基础上大幅修改重构 |

**再分发请保留上述署名**，并自行确认 PANNs（CC BY 4.0）与 YAMNet（Apache 2.0）的署名要求。WorkBuddy、CodeBuddy 等名称与商标归腾讯所有，本项目不包含其客户端代码。

## 免责声明

- 本项目是**个人技术研究**用途的第三方工具，与腾讯官方无关；
- 使用非官方客户端接入可能违反 WorkBuddy 服务条款，**账号风险由使用者自行承担**；
- 请勿商业分发或大规模共享；
- 自签名 APK，安装时系统提示「未知来源 / 未经安全检测」属正常现象。

## Star History

如果这个项目对你有用，点个 Star 支持一下 ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=LJRNN/WorkBuddyGateway&type=Date)](https://star-history.com/#LJRNN/WorkBuddyGateway&Date)
