#!/usr/bin/env sh
# 构建 release APK：./build.sh
# 需要 JDK 17+，以及通过 ANDROID_HOME（或 local.properties 的 sdk.dir）指定的 Android SDK。
set -eu
cd "$(dirname "$0")"
exec ./gradlew --no-daemon :app:assembleRelease "$@"
