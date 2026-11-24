"""Build a single selectable Add to queue patch from this checkout's :patches:buildAndroid bundle."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
QUEUE = "app/morphe/extension/youtube/patches/queue"
TEST = "app/morphe/extension/youtube/queuesavetest"
ENTRY = "app/morphe/patches/youtube/layout/flyout/AddToQueuePatchKt"
PROTO = Path("app/morphe/extension/shared/innertube")
FIX = "f0c2e43ba0748e292bc153137b4b28649239966b"


def put(archive, name, data):
    archive.writestr(ZipInfo(name, (2026, 11, 23, 0, 0, 0)), data, compress_type=ZIP_DEFLATED)


def jar_from_tree(output, base, subtree):
    with ZipFile(output, "w") as archive:
        for file in sorted((base / subtree).rglob("*.class")):
            put(archive, file.relative_to(base).as_posix(), file.read_bytes())


def compile_picker(work, tools):
    # The stock stable and dev bundles use different Java packages for their queue protobufs.
    # Keep the wire schema from the fix in a private package, and share only the stock Picker interface.
    source = (ROOT / "extensions/youtube/src/main/java" / QUEUE / "QueueSaveActions.java").read_text(encoding="utf-8")
    picker = source[source.index("    public interface Picker"):source.index("    private static final class")]
    runtime = (source[:source.index(picker)]
               + source[source.index("    private static final class"):source.index("    static NavigationEndpoint openPicker")]
               + source[source.index("    /** Bind the actual"):source.index("    /** The native edit callback")] + "}\n")
    runtime = runtime.replace("package " + QUEUE.replace("/", ".") + ";",
                              "package " + TEST.replace("/", ".") + ";\nimport "
                              + QUEUE.replace("/", ".") + ".QueueSaveActions.Picker;")
    runtime = runtime.replace("app.morphe.extension.shared.innertube.queue.", TEST.replace("/", ".") + ".proto.queue.")
    runtime = re.sub(r"import [^\n]+(?:AddToPlaylistEndpoint|QueueSaveEditResponse);\n", "", runtime)
    src = work / "src"
    (src / "stub").mkdir(parents=True)
    (src / "QueueSaveActions.java").write_text(runtime, encoding="utf-8")
    (src / "stub/QueueSaveActions.java").write_text(
        f"package {QUEUE.replace('/', '.')};\nimport java.util.List;\nimport com.google.protobuf.MessageLite;\n"
        + "public final class QueueSaveActions {\n" + picker + "}\n", encoding="utf-8")
    with ZipFile(tools.extensions) as aar:
        (work / "extension-api.jar").write_bytes(aar.read("classes.jar"))
    (work / "proto" / PROTO).mkdir(parents=True)
    generated = work / "generated"
    generated.mkdir()
    schemas = []
    for name in ("icon.proto", "playlist_queue.proto"):
        path = PROTO / name
        text = (ROOT / "extensions/shared-youtube/library/src/main/proto" / path).read_text(encoding="utf-8")
        text = text.replace('option java_package = "app.morphe.extension.shared.innertube',
                            'option java_package = "' + TEST.replace("/", ".") + '.proto')
        (work / "proto" / path).write_text(text, encoding="utf-8")
        schemas.append(work / "proto" / path)
    subprocess.run([tools.protoc, "--proto_path=" + str(work / "proto"), "--java_out=lite:" + str(generated),
                    *schemas], check=True)
    classes = work / "runtime-classes"
    classes.mkdir()
    cp = os.pathsep.join(map(str, [tools.protobuf, work / "extension-api.jar", tools.annotations, tools.android, tools.desktop]))
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", cp, "-d", classes,
                    *sorted(src.rglob("*.java")), *sorted(generated.rglob("*.java"))], check=True)
    jar_from_tree(work / "runtime.jar", classes, TEST)
    return dex(work, tools, "runtime", [work / "extension-api.jar", tools.protobuf])


def dex(work, tools, name, libraries):
    output = work / (name + "-dex")
    output.mkdir()
    args = ["java", "-Xmx2g", "-cp", tools.d8, "com.android.tools.r8.D8", "--release", "--min-api", "26",
            "--lib", tools.android]
    for library in libraries:
        args.extend(["--classpath", library])
    subprocess.run([*args, "--output", output, work / (name + ".jar")], check=True)
    return (output / "classes.dex").read_bytes()


def compile_patch(work, tools, bundle):
    with ZipFile(work / "patch-classes.jar", "w") as archive:
        for name in bundle.namelist():
            if name.endswith(".class") and name.startswith(("app/morphe/patches/", "app/morphe/util/")):
                put(archive, name, bundle.read(name))
    rule = ("-dontoptimize\n-dontobfuscate\n-dontwarn **\n"
            "-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations\n"
            "-keep class " + ENTRY.replace("/", ".") +
            " { public static app.morphe.patcher.patch.Patch getAddToQueuePatch(); }\n")
    (work / "keep.pro").write_text(rule, encoding="utf-8")
    subprocess.run(["java", "-Xmx3g", "-cp", tools.d8, "com.android.tools.r8.R8", "--classfile", "--release",
                    "--lib", os.environ["JAVA_HOME"], "--lib", tools.desktop, "--pg-conf", work / "keep.pro",
                    "--output", work / "closure.jar", work / "patch-classes.jar"], check=True)
    classes = work / "patch-classes"
    classes.mkdir()
    with ZipFile(work / "closure.jar") as archive:
        for entry in archive.namelist():
            if entry.endswith(".class"):
                archive.extract(entry, classes)
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", tools.desktop,
                    "-d", classes, HERE / "QueueTest.java"], check=True)
    subprocess.run(["java", "-cp", tools.asm, HERE / "WrapEntry.java", classes / (ENTRY + ".class")], check=True)
    jar_from_tree(work / "patch.jar", classes, ".")
    return dex(work, tools, "patch", [tools.desktop])


def include_resource(name):
    if name.endswith("/"):
        return False
    if name in {"extensions/shared.mpe", "extensions/shared-youtube.mpe", "extensions/youtube.mpe"}:
        return True
    if name.startswith(("settings/", "license/")):
        return True
    scopes = {"shared", "shared-youtube", "youtube"}
    parts = Path(name).parts
    return ((parts[0] == "addresources-json" and Path(name).stem in scopes)
            or (parts[0] == "addresources" and len(parts) > 2 and parts[2] in scopes))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for tool in ("bundle", "desktop", "android", "d8", "asm", "protoc", "protobuf", "extensions", "annotations", "output"):
        parser.add_argument("--" + tool, type=Path, required=True)
    tools = parser.parse_args()
    with tempfile.TemporaryDirectory() as temporary, ZipFile(tools.bundle) as bundle:
        work = Path(temporary).resolve()
        picker = compile_picker(work, tools)
        patch = compile_patch(work, tools, bundle)
        manifest = ["Manifest-Version: 1.0", "Name: YouTube queue save test",
                    "Description: Standalone Add to queue replacement for issue 4812", "Version: 2.4.0-queue-save.4812.1",
                    "Source: https://github.com/zappybiby/morphe-patches", "Source-Commit: " + FIX, "Author: MorpheApp",
                    "License: GNU General Public License v3.0, with additional GPL section 7 ", " requirements",
                    "Patcher-Version: 2.5.0", "", ""]
        with ZipFile(tools.output, "w") as result, ZipFile(work / "patch.jar") as java:
            put(result, "META-INF/MANIFEST.MF", "\r\n".join(manifest).encode())
            for name in ("LICENSE", "NOTICE"):
                put(result, name, (ROOT / name).read_bytes())
            put(result, "classes.dex", patch)
            put(result, "extensions/queue-save-test.mpe", picker)
            for name in sorted(java.namelist()):
                put(result, name, java.read(name))
            for name in sorted(filter(include_resource, bundle.namelist())):
                put(result, name, bundle.read(name))


if __name__ == "__main__":
    main()
