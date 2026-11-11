import argparse
from collections import defaultdict
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

PACKAGE = "app/morphe/patches/music/misc/androidauto/"
PATCH_CLASS = PACKAGE + "AndroidAutoPatchKt"
EXTENSION = "extensions/android-auto-4680.mpe"
SOURCE_ROOT = Path("patches/src/main/kotlin") / PACKAGE
OLD_DESCRIPTOR = "Lapp/morphe/extension/music/patches/AndroidAuto"
ADDON_DESCRIPTOR = "Lapp/zappybiby/extension/music/androidauto/AndroidAuto"


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError(f"Expected one occurrence of {old!r}")
    return source.replace(old, new)


def adapt_kotlin(repo, output):
    for file in sorted((repo / SOURCE_ROOT).glob("*.kt")):
        code = file.read_text().replace(OLD_DESCRIPTOR, ADDON_DESCRIPTOR)
        if file.name == "AndroidAutoPatch.kt":
            code = replace_once(
                code,
                "import app.morphe.patches.music.misc.extension.sharedExtensionPatch\n",
                "",
            )
            code = replace_once(
                code,
                "import app.morphe.patches.music.misc.settings.settingsPatch",
                "import app.morphe.patches.music.misc.playservice.versionCheckPatch",
            )
            code = replace_once(
                code,
                "    dependsOn(\n        sharedExtensionPatch,\n        settingsPatch\n    )",
                f'    dependsOn(versionCheckPatch)\n    extendWith("{EXTENSION}")',
            )
            code = replace_once(
                code,
                'name = "Android Auto",',
                'name = "Android Auto (empty root test)",',
            )
        (output / file.name).write_text(code)


def overlay_classes(baseline, classes, output):
    with ZipFile(baseline) as original, ZipFile(output, "w", ZIP_DEFLATED) as archive:
        for entry in sorted(original.namelist()):
            if entry.endswith(".class") and not entry.startswith(PACKAGE):
                archive.writestr(entry, original.read(entry))
        for file in sorted((classes / PACKAGE).glob("*.class")):
            archive.write(file, file.relative_to(classes).as_posix())


def extract_closure(input_jar, jdeps, output):
    with ZipFile(input_jar) as archive:
        entries = {
            path[:-6].replace("/", "."): path
            for path in archive.namelist()
            if path.endswith(".class") and not path.startswith("META-INF/versions/")
        }
        references = defaultdict(set)
        for line in jdeps.read_text().splitlines():
            parts = line.split()
            if len(parts) >= 4 and parts[0] in entries and parts[2] in entries:
                references[parts[0]].add(parts[2])

        reachable = set()
        pending = [PATCH_CLASS.replace("/", ".")]
        while pending:
            current = pending.pop()
            if current not in reachable:
                reachable.add(current)
                pending.extend(references[current] - reachable)

        with ZipFile(output, "w", ZIP_DEFLATED) as result:
            for class_name in sorted(reachable):
                entry = entries[class_name]
                result.writestr(entry, archive.read(entry))


def assemble(jvm, dex, runtime, repo, output):
    manifest = (
        "Manifest-Version: 1.0",
        "Name: Android Auto empty root test add-on",
        "Description: Android Auto issue 4680 test patch for YouTube Music",
        "Version: 2.3.0-android-auto.4680.1",
        "Timestamp: 1794405819000",
        "Source: https://github.com/zappybiby/morphe-patches",
        "Source-Commit: 712ea4ffd4a7c4e55dbc1411bb8f7b022b8ae5bf",
        "Author: MorpheApp",
        "Contact: na",
        "Website: https://morphe.software",
        "License: GNU General Public License v3.0, with additional GPL section 7 ",
        " requirements",
        "Patcher-Version: 2.3.0",
        "",
        "",
    )
    with ZipFile(jvm) as java, ZipFile(output, "w", ZIP_DEFLATED) as out:
        def add(path, data):
            info = ZipInfo(path, date_time=(2026, 11, 11, 9, 3, 39))
            out.writestr(info, data, compress_type=ZIP_DEFLATED, compresslevel=9)

        add("META-INF/MANIFEST.MF", "\r\n".join(manifest).encode())
        for name in ("LICENSE", "NOTICE"):
            add(name, (repo / name).read_bytes())
        add("classes.dex", dex.read_bytes())
        add(EXTENSION, runtime.read_bytes())
        for entry in sorted(java.namelist()):
            if entry.endswith(".class"):
                add(entry, java.read(entry))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Package the Android Auto issue 4680 test source.")
    commands = parser.add_subparsers(dest="command", required=True)
    for handler, arguments in (
        (adapt_kotlin, ("repo", "output")),
        (overlay_classes, ("baseline", "classes", "output")),
        (extract_closure, ("input_jar", "jdeps", "output")),
        (assemble, ("jvm", "dex", "runtime", "repo", "output")),
    ):
        command = commands.add_parser(handler.__name__)
        for argument in arguments:
            command.add_argument(argument, type=Path)
        command.set_defaults(handler=handler)

    args = vars(parser.parse_args())
    args.pop("command")
    args.pop("handler")(**args)
