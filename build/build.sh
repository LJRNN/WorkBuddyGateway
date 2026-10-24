#!/usr/bin/env bash
# 打包 WorkBuddy 网关 APK
#   用法: ./build.sh          # 完整版（含音频模型）
#         ./build.sh lite     # 轻量版（无音频模型）
#
# 依赖环境变量:
#   ANDROID_HOME     Android SDK 根目录（需 build-tools/34.0.0、platforms/android-33）
#   ANDROID_NDK_HOME Android NDK r26+ 根目录
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="${1:-full}"
SDK="${ANDROID_HOME:?请设置 ANDROID_HOME}"
NDK="${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-33/android.jar"
NDK_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
WORK="$ROOT/.build"
OUT="$ROOT/dist"

VERSION="$(grep -o 'android:versionName="[^"]*"' "$ROOT/app/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
if [ "$VARIANT" = "lite" ]; then
  NAME="WorkBuddyGateway-v${VERSION}-lite"
  MANIFEST="$ROOT/app/AndroidManifest-lite.xml"
else
  NAME="WorkBuddyGateway-v${VERSION}"
  MANIFEST="$ROOT/app/AndroidManifest.xml"
fi
echo "==> 构建 $NAME"

rm -rf "$WORK" && mkdir -p "$WORK"/{classes,dex,res,lib/arm64-v8a,lib/armeabi-v7a,assets} "$OUT"

# ---------- 1. Go 网关 → jniLibs ----------
echo "==> 交叉编译 Go"
cd "$ROOT/gateway"
for arch in arm64:arm64-v8a:aarch64-linux-android26 armv7:armeabi-v7a:armv7a-linux-androideabi26; do
  GOARCH="${arch%%:*}"; ABI="$(echo "$arch" | cut -d: -f2)"; CC_TRIPLE="$(echo "$arch" | cut -d: -f3)"
  for cmd in server login; do
    lib="libwb2api.so"; [ "$cmd" = "login" ] && lib="libwb2login.so"
    GOOS=android GOARCH="$GOARCH" CGO_ENABLED=1 CC="$NDK_BIN/${CC_TRIPLE}-clang" \
      go build -trimpath -ldflags "-s -w" -o "$WORK/lib/$ABI/$lib" "./cmd/$cmd"
  done
done

# ---------- 2. 资源 ----------
echo "==> 编译资源"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$WORK/res/"

# ---------- 3. Java → dex ----------
echo "==> 编译 Java"
find "$ROOT/app" -maxdepth 1 -name '*.java' > "$WORK/sources.txt"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$ROOT/app/libs/tflite-all.jar" \
  -d "$WORK/classes" @"$WORK/sources.txt" 2>&1 | grep -v "^注:" || true
"$BT/d8" --release --lib "$ANDROID_JAR" --min-api 26 \
  --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

# ---------- 4. 模型 / 资源文件 ----------
cp "$ROOT/build/test.mp4" "$WORK/assets/"
if [ "$VARIANT" != "lite" ]; then
  cp "$ROOT/app/models"/*.tflite "$ROOT/app/models"/*.txt "$ROOT/app/models"/*.bin "$WORK/assets/" 2>/dev/null || true
fi
for f in "$WORK/assets"/*; do [ -e "$(dirname "$f")/.nomedia" ] || touch "$(dirname "$f")/.nomedia"; done

# ---------- 5. 链接 + 打包 ----------
echo "==> 链接"
"$BT/aapt2" link -o "$WORK/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --min-sdk-version 26 --target-sdk-version 33 \
  -A "$WORK/assets" \
  "$WORK"/res/*.flat

cd "$WORK"
mkdir -p stage && cp dex/classes.dex stage/ && cp -r lib stage/lib
(cd stage && zip -q -r ../base.apk classes.dex lib)

# ---------- 6. 对齐 + 签名 ----------
echo "==> 签名"
"$BT/zipalign" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign \
  --ks "${KEYSTORE:-$ROOT/build/wb.keystore}" \
  --ks-pass "pass:${KEYSTORE_PASS:-wbgateway}" \
  --ks-key-alias "${KEY_ALIAS:-wb}" \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/$NAME.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/$NAME.apk" >/dev/null

ls -lh "$OUT/$NAME.apk"
sha256sum "$OUT/$NAME.apk"
