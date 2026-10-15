#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
jdk=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
sdk=${ANDROID_HOME:-/opt/android-sdk}
output="$repo/app/build/parser-fixture"
android_jar="$sdk/platforms/android-37.0/android.jar"
if [[ ! -f "$android_jar" ]]; then
    echo "Android API 37 platform is required" >&2
    exit 1
fi
mkdir -p "$output/classes" "$output/dex"
"$jdk/bin/javac" --release 8 -cp "$android_jar" -d "$output/classes" "$repo"/app/src/androidTest/fixtures/com/github/catvod/parser/*.java
"$jdk/bin/jar" cf "$output/parser-classes.jar" -C "$output/classes" .
"$jdk/bin/java" -cp "$sdk/build-tools/37.0.0/lib/d8.jar" com.android.tools.r8.D8 --lib "$android_jar" --min-api 21 --output "$output/dex" "$output/parser-classes.jar"
"$jdk/bin/jar" cf "$repo/app/src/androidTest/assets/fixture-parser.jar" -C "$output/dex" classes.dex
