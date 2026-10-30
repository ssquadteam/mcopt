#!/bin/sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
work=${SHADERPACK_WORK:-${TMPDIR:-/tmp}/mcopt-shaderpack}
mkdir -p "$work/classes" "$work/out"
lw=$HOME/.gradle/caches/modules-2/files-2.1/org.lwjgl
cp=$(find "$lw/lwjgl/3.4.3" "$lw/lwjgl-shaderc/3.4.3" "$lw/lwjgl-spvc/3.4.3" -name "*.jar" ! -name "*sources*" ! -name "*natives-macos.jar" | tr '\n' ':')
cp=$cp$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jspecify" -name "jspecify-1.0.0.jar" | head -1)
java=${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}/bin
"$java/javac" -nowarn -d "$work/classes" -cp "$cp" "$root"/metal/src/main/java/mcopt/metal/shaderpack/*.java "$here/Check.java"
clang -fobjc-arc -O1 -framework Foundation -framework Metal -o "$work/mslcheck" "$here/mslcheck.m"
"$java/java" -Dmslcheck="$work/mslcheck" $CHECK_OPTS -cp "$work/classes:$cp" Check "$work/out" "$@"
