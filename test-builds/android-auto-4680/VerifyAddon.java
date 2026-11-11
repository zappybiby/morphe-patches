import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import java.util.TreeSet;

final class VerifyAddon {
    private static final String PATCH = "Lapp/morphe/patcher/patch/Patch;";
    private static final String NAME = "Android Auto (empty root test)";
    private static final String RUNTIME = "Lapp/zappybiby/extension/music/androidauto/";
    private static final int PUBLIC_STATIC = AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue();

    private static boolean publicStatic(int flags) {
        return (flags & PUBLIC_STATIC) == PUBLIC_STATIC;
    }

    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexFile(new File(args[0]), null);
        var names = new TreeSet<String>();
        try (var loader = new URLClassLoader(
                new URL[]{new File(args[2]).toURI().toURL()}, VerifyAddon.class.getClassLoader())) {
            for (var definition : dex.getClasses()) {
                String descriptor = definition.getType();
                String className = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                for (var method : definition.getMethods()) {
                    if (!publicStatic(method.getAccessFlags()) || !method.getParameterTypes().isEmpty()
                            || !method.getReturnType().equals(PATCH)) continue;
                    Object patch = loader.loadClass(className).getMethod(method.getName()).invoke(null);
                    collectName(patch, names);
                }
                for (var field : definition.getFields()) {
                    if (!publicStatic(field.getAccessFlags()) || !field.getType().equals(PATCH)) continue;
                    Object patch = loader.loadClass(className).getField(field.getName()).get(null);
                    collectName(patch, names);
                }
            }
        }

        if (!names.equals(Set.of(NAME))) throw new IllegalStateException("Unexpected DEX patches: " + names);
        var runtime = DexFileFactory.loadDexFile(new File(args[1]), null);
        boolean hasAndroidAuto = false;
        for (var type : runtime.getClasses()) {
            String descriptor = type.getType();
            if (descriptor.equals(RUNTIME + "AndroidAutoPatch;")) hasAndroidAuto = true;
            if (!descriptor.startsWith(RUNTIME) && !descriptor.startsWith("Lcom/android/tools/r8/")) {
                throw new IllegalStateException("Unexpected extension class: " + descriptor);
            }
        }
        if (!hasAndroidAuto) throw new IllegalStateException("Missing relocated Android Auto runtime");
        System.out.println("DEX exposes " + NAME + " and contains the relocated runtime");
    }

    private static void collectName(Object patch, Set<String> names) throws Exception {
        String name = (String) patch.getClass().getMethod("getName").invoke(patch);
        if (name != null && !names.add(name)) throw new IllegalStateException("Duplicate DEX patch: " + name);
    }
}
