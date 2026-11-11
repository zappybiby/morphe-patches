import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

final class RelocateRuntime {
    private static final String ORIGINAL = "app/morphe/extension/music/patches/";
    private static final String ADDON = "app/zappybiby/extension/music/androidauto/";
    private static final Set<String> FAMILIES = Set.of(
        "AndroidAutoPatch",
        "AndroidAutoBrowseResponseParser",
        "AndroidAutoSearchPatch",
        "AndroidAutoSearchResponseParser",
        "ProtoMessage"
    );

    private static String family(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    public static void main(String[] args) throws Exception {
        var directory = Path.of(args[0]).resolve(ORIGINAL);
        var remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (!internalName.startsWith(ORIGINAL)) return internalName;
                String className = internalName.substring(ORIGINAL.length());
                if (FAMILIES.contains(family(className))) return ADDON + className;
                return internalName;
            }
        };

        var found = new HashSet<String>();
        try (var paths = Files.list(directory);
             var jar = new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (Path path : paths.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".class")) continue;
                String className = name.substring(0, name.length() - ".class".length());
                if (!FAMILIES.contains(family(className))) continue;
                var reader = new ClassReader(Files.readAllBytes(path));
                var writer = new ClassWriter(0);
                reader.accept(new ClassRemapper(writer, remapper), 0);
                var entry = new ZipEntry(ADDON + name);
                entry.setTime(0);
                jar.putNextEntry(entry);
                jar.write(writer.toByteArray());
                jar.closeEntry();
                found.add(className);
            }
        }

        if (!found.containsAll(FAMILIES)) {
            var missing = new HashSet<>(FAMILIES);
            missing.removeAll(found);
            throw new IllegalStateException("Gradle's Music runtime is missing classes " + missing);
        }
    }
}
