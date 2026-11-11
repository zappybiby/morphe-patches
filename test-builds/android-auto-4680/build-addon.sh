#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 5 ]]; then
    echo "Usage: $0 <source checkout> <Gradle .mpp> <Gradle Music javac output> <android.jar> <output .mpp>" >&2
    exit 2
fi

recipe=$(cd -- "$(dirname -- "$0")" && pwd)
source_root=$(realpath "$1")
full_bundle=$(realpath "$2")
music_classes=$(realpath "$3")
android_jar=$(realpath "$4")
output=$(realpath -m "$5")
source_commit=712ea4ffd4a7c4e55dbc1411bb8f7b022b8ae5bf
kotlin_package=patches/src/main/kotlin/app/morphe/patches/music/misc/androidauto
runtime_package=extensions/music/src/main/java/app/morphe/extension/music/patches
git -C "$source_root" diff --exit-code "$source_commit" -- "$kotlin_package" "$runtime_package"

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
tools=${MORPHE_ADDON_TOOLS:-"$work/tools"}
mkdir -p "$tools" "$work/kotlin-src" "$work/patch-dex" "$work/runtime-dex"

fetch() {
    local file=$1
    local url=$2
    local sha256=$3
    if [[ ! -f "$tools/$file" ]]; then
        curl --fail --location --silent --show-error --retry 3 "$url" --output "$tools/$file"
    fi
    printf '%s  %s\n' "$sha256" "$tools/$file" | sha256sum --check --status
}

maven=https://repo.maven.apache.org/maven2
kotlin_maven="$maven/org/jetbrains/kotlin"
desktop_url=https://github.com/MorpheApp/morphe-desktop/releases/download/v2.2.0-dev.12/morphe-desktop-2.2.0-dev.12-all.jar

fetch desktop.jar "$desktop_url" 67f50310d867cedccca5bf35999a624803b3a947a23a8f6c4b350b9bb960b861
fetch r8.jar https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.1.31/r8-9.1.31.jar 3b4de3053885da105e39c15212261d22653d6d1b5eb92323dd04ae913cc8286f
fetch kotlin-compiler.jar "$kotlin_maven/kotlin-compiler-embeddable/2.4.0/kotlin-compiler-embeddable-2.4.0.jar" c2b25e8c1c93ec416ba4327f5e31eaec0f0c8847241b9353e294b8db9dce564f
fetch kotlin-build-tools-api.jar "$kotlin_maven/kotlin-build-tools-api/2.4.0/kotlin-build-tools-api-2.4.0.jar" 66917335497f9ab75a36c34b3d90bad2bc8dc256c34b2413ecd0c9467fcf597d
fetch kotlin-script-runtime.jar "$kotlin_maven/kotlin-script-runtime/2.4.0/kotlin-script-runtime-2.4.0.jar" 196e8ecbf63daf477e34e67c2f871ee232ae7bf8b377432d876d1bbf9d273de3
fetch jetbrains-annotations.jar "$maven/org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar" 2037be378980d3ba9333e97955f3b2cde392aa124d04ca73ce2eee6657199297
fetch asm.jar "$maven/org/ow2/asm/asm/9.8/asm-9.8.jar" 876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051
fetch asm-commons.jar "$maven/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar" 3301a1c1cb4c59fcc5292648dac1d7c5aed4c0f067dfbe88873b8cdfe77404f4

java_home=$(dirname -- "$(dirname -- "$(readlink -f "$(command -v java)")")")
ln -s "$full_bundle" "$work/full.jar"
python3 "$recipe/prepare.py" adapt_kotlin "$source_root" "$work/kotlin-src"

compiler="$tools/kotlin-compiler.jar:$tools/kotlin-build-tools-api.jar:$tools/kotlin-script-runtime.jar"
patcher="$tools/desktop.jar:$tools/jetbrains-annotations.jar"
java -Xmx1100m -cp "$compiler:$patcher" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -module-name app.morphe_patches \
    -Xfriend-paths="$work/full.jar" -classpath "$work/full.jar:$patcher" \
    -d "$work/kotlin" "$work"/kotlin-src/*.kt

python3 "$recipe/prepare.py" overlay_classes "$work/full.jar" "$work/kotlin" "$work/overlay.jar"
java -Xmx1100m --module jdk.jdeps/com.sun.tools.jdeps.Main -verbose:class -filter:none \
    --multi-release 17 -cp "$patcher" "$work/overlay.jar" > "$work/class-deps.txt"
python3 "$recipe/prepare.py" extract_closure "$work/overlay.jar" "$work/class-deps.txt" "$work/closure.jar"

java -Xmx1200m -cp "$tools/r8.jar" com.android.tools.r8.R8 --classfile \
    --lib "$java_home" --lib "$tools/desktop.jar" --lib "$tools/jetbrains-annotations.jar" \
    --pg-conf "$recipe/keep-android-auto.pro" --output "$work/narrow.jar" "$work/closure.jar"
java -Xmx800m -cp "$tools/r8.jar" com.android.tools.r8.D8 --release --min-api 26 \
    --classpath "$tools/desktop.jar" --classpath "$tools/jetbrains-annotations.jar" \
    --output "$work/patch-dex" "$work/narrow.jar"

java -cp "$tools/asm.jar:$tools/asm-commons.jar" "$recipe/RelocateRuntime.java" \
    "$music_classes" "$work/runtime.jar"
java -Xmx800m -cp "$tools/r8.jar" com.android.tools.r8.D8 --release --min-api 26 \
    --lib "$android_jar" --classpath "$tools/desktop.jar" --classpath "$music_classes" \
    --output "$work/runtime-dex" "$work/runtime.jar"

mkdir -p "$(dirname -- "$output")"
python3 "$recipe/prepare.py" assemble "$work/narrow.jar" "$work/patch-dex/classes.dex" \
    "$work/runtime-dex/classes.dex" "$source_root" "$output"

java -cp "$tools/desktop.jar" "$recipe/VerifyAddon.java" "$work/patch-dex/classes.dex" \
    "$work/runtime-dex/classes.dex" "$output"

patches=$(java -jar "$tools/desktop.jar" list-patches --patches "$output" 2>&1)
printf '%s\n' "$patches"
test "$(sed -n 's/^Name: //p' <<< "$patches")" = 'Android Auto (empty root test)'
sha256sum "$output"
