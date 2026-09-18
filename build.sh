#!/bin/bash
# ---------------------------------------------------------------------------
# 编译「火花助手」APK。
#
# 纯手工链路：aapt v1 + javac + R8/D8 + zipalign + apksigner。
# 故意不用 Gradle / AndroidX —— 这套组合在 arm64 安卓手机上也能原生跑
# （官方 build-tools 只发 x86_64，Gradle + AGP 在手机里跑不起来）。
#
#   ./build.sh                          # 缺什么自动去 Google 下载到 vendor/
#   SDKROOT=/opt/android ./build.sh     # 用自己准备的 r8.jar + platform-*/android.jar
#   KEYSTORE_PASS=xxx ./build.sh        # 换个密钥库口令
#
# 依赖：openjdk-17+、aapt、apksigner、zipalign、unzip、curl
#   Debian/Ubuntu: apt install aapt apksigner zipalign openjdk-17-jdk-headless unzip curl
# ---------------------------------------------------------------------------
set -euo pipefail

BASE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$BASE/proj"
BUILD="$BASE/build"
OUT="$BASE/out"
VENDOR="${SDKROOT:-$BASE/vendor}"
R8="$VENDOR/r8.jar"
R8_URL="https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.4.17/r8-9.4.17.jar"
PLATFORM_URL="https://dl.google.com/android/repository/platform-32_r01.zip"
KS="${KEYSTORE:-$BASE/spark.keystore}"
KS_PASS="${KEYSTORE_PASS:-spark123456}"
KS_ALIAS="${KEYSTORE_ALIAS:-spark}"

VERSION="$(grep -o 'android:versionName="[^"]*"' "$PROJ/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
APP="spark-helper-v$VERSION.apk"

echo "=============================================="
echo " 编译火花助手 v$VERSION"
echo "=============================================="

for t in aapt javac java keytool zipalign apksigner unzip curl; do
  command -v "$t" >/dev/null || {
    echo "缺少工具：$t" >&2
    echo "Debian/Ubuntu: apt install aapt apksigner zipalign openjdk-17-jdk-headless unzip curl" >&2
    exit 1
  }
done

# --- 依赖：R8 / D8 --------------------------------------------------------
if [ ! -f "$R8" ]; then
  echo "[deps] 下载 r8.jar（约 20 MB）…"
  mkdir -p "$VENDOR"
  curl -fL --retry 3 --connect-timeout 20 -o "$R8" "$R8_URL"
fi

# --- 依赖：平台包（android.jar）-------------------------------------------
# aapt v1（AOSP）读不了 API 33+ 的 resources.arsc，实测 API 32 可用。
# 这里逐个实测而不是看版本号，因为「新」不等于「能解析」。
ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ]; then
  if ! ls "$VENDOR"/platform-*/*/android.jar >/dev/null 2>&1; then
    echo "[deps] 下载 platform-32（约 60 MB）…"
    mkdir -p "$VENDOR"
    curl -fL --retry 3 --connect-timeout 20 -o "$VENDOR/platform-32.zip" "$PLATFORM_URL"
    unzip -q -o "$VENDOR/platform-32.zip" -d "$VENDOR/platform-32"
  fi
  for j in $(ls -d "$VENDOR"/platform-*/*/android.jar 2>/dev/null | sort -rV); do
    probe="$(mktemp -d)"
    if aapt package -f -m -J "$probe" -M "$PROJ/AndroidManifest.xml" -S "$PROJ/res" \
        -I "$j" >/dev/null 2>&1 && [ -n "$(find "$probe" -name '*.java' -print -quit)" ]; then
      ANDROID_JAR="$j"; rm -rf "$probe"; break
    fi
    rm -rf "$probe"
  done
fi
[ -f "${ANDROID_JAR:-}" ] || { echo "找不到可用的 android.jar（可用 ANDROID_JAR= 指定）" >&2; exit 1; }
echo " android.jar : $ANDROID_JAR"
echo " r8.jar      : $R8"

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

echo
echo "[1/6] aapt 生成 R.java"
aapt package -f -m -J "$BUILD/gen" -M "$PROJ/AndroidManifest.xml" -S "$PROJ/res" -I "$ANDROID_JAR"

echo "[2/6] javac 编译"
find "$PROJ/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
echo "      源文件 $(wc -l < "$BUILD/sources.txt") 个"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[3/6] D8 生成 classes.dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 23 \
  --lib "$ANDROID_JAR" --output "$BUILD/dex" @"$BUILD/classes.txt"
ls -l "$BUILD/dex"

echo "[4/6] 打包资源 + dex"
aapt package -f -M "$PROJ/AndroidManifest.xml" -S "$PROJ/res" -I "$ANDROID_JAR" \
  -F "$BUILD/app.unaligned.apk"
(cd "$BUILD/dex" && aapt add "$BUILD/app.unaligned.apk" classes.dex >/dev/null)

echo "[5/6] zipalign"
zipalign -f -p 4 "$BUILD/app.unaligned.apk" "$BUILD/app.aligned.apk"

echo "[6/6] 签名"
if [ ! -f "$KS" ]; then
  echo "      生成新密钥库 $KS"
  keytool -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Spark Helper, O=Spark, C=CN" 2>&1 | tail -1
fi
apksigner sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUT/$APP" "$BUILD/app.aligned.apk"
rm -f "$OUT/$APP.idsig"

echo
apksigner verify --verbose --print-certs "$OUT/$APP" | head -8
aapt dump badging "$OUT/$APP" | grep -E "^(package|sdkVersion|targetSdkVersion|application-label|launchable-activity)" || true
echo
ls -l "$OUT/$APP"
echo "SHA-256: $(sha256sum "$OUT/$APP" | cut -d' ' -f1)"
echo "构建完成：$OUT/$APP"
