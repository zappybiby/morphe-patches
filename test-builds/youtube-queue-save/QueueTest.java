package app.zappybiby.patches.queue;

import app.morphe.patcher.PatcherContext;
import app.morphe.patcher.extensions.InstructionExtensions;
import app.morphe.patcher.patch.Patch;
import app.morphe.patcher.patch.PatchFunctionsKt;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Supplier;
import kotlin.Unit;

public final class QueueTest {
    private static final String ENTRY = "app.morphe.patches.youtube.layout.flyout.AddToQueuePatchKt";
    private static final String ORIGINAL = "Lapp/morphe/extension/youtube/patches/queue/QueueSaveActions";
    private static Patch replacement;

    public static synchronized Patch wrap(Patch bundledQueue) {
        if (replacement != null) return replacement;
        var queue = new QueueDependency(bundledQueue);
        Supplier<InputStream> runtime = () -> Objects.requireNonNull(
                QueueTest.class.getResourceAsStream("/extensions/queue-save-test.mpe"));
        replacement = new Patch("Add to queue (test)", bundledQueue.getDescription(), true, queue,
                bundledQueue.getCompatibility(), Collections.emptySet(), ignored -> {
                    if (queue.resolve() != bundledQueue) applyPickerFix();
                    return Unit.INSTANCE;
                }, null, bundledQueue.getAvailability(), bundledQueue.getCategory(),
                Collections.singletonList(() -> queue.resolve() == bundledQueue
                        ? Collections.emptyList() : Collections.singletonList(runtime)));
        return replacement;
    }

    private static final class QueueDependency extends AbstractSet<Patch> {
        private final Patch bundled;
        QueueDependency(Patch bundled) { this.bundled = bundled; }
        @Override public int size() { return 1; }
        @Override public Iterator<Patch> iterator() { return Collections.singleton(resolve()).iterator(); }

        Patch resolve() {
            var context = PatcherContext.Companion.getCurrent$morphe_patcher();
            if (context == null || replacement == null) return bundled;
            var selected = context.getExecutablePatches$morphe_patcher();
            if (!selected.contains(replacement)) return bundled;
            boolean stockSelected = false;
            for (var patch : selected) {
                var bundle = patch.getBundle$morphe_patcher();
                if (patch == replacement || bundle == null || !"Morphe Patches".equals(bundle.getName())) continue;
                stockSelected = true;
                if ("Add to queue".equals(patch.getName())) return patch;
                var loader = declaringLoader(patch);
                if (loader == null || loader == QueueTest.class.getClassLoader()) continue;
                try {
                    // The stock queue owns the same settings and feed hooks as its selected siblings.
                    // Returning that instance lets Patcher run each dependency and finalizer once.
                    return (Patch) Class.forName(ENTRY, false, loader).getMethod("getAddToQueuePatch").invoke(null);
                } catch (ClassNotFoundException ignored) {
                    // A patch with an empty execute block can point to Patcher itself.
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Could not load Add to queue from Morphe Patches", ex);
                }
            }
            if (stockSelected) throw new IllegalStateException("Could not find the Morphe Patches queue dependency");
            return bundled;
        }

        private static ClassLoader declaringLoader(Patch patch) {
            try {
                Field field = Patch.class.getDeclaredField("executeBlock");
                field.setAccessible(true);
                for (Object callback : Arrays.asList(field.get(patch), patch.getFinalizeBlock$morphe_patcher())) {
                    if (callback == null) continue;
                    if (callback.getClass().getName().equals("app.morphe.patcher.patch.ContextFreeBlock")) {
                        Method getBlock = callback.getClass().getDeclaredMethod("getBlock");
                        getBlock.setAccessible(true);
                        callback = getBlock.invoke(callback);
                    }
                    var loader = callback.getClass().getClassLoader();
                    if (loader != Patch.class.getClassLoader()) return loader;
                }
                return null;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not locate the selected Morphe patch source", e);
            }
        }
    }

    private static void applyPickerFix() {
        var owner = PatchFunctionsKt.mutableClassDefBy(ORIGINAL + ";");
        var entry = owner.getMethods().stream().filter(m -> m.getName().equals("onPickerResponse")
                && m.getParameterTypes().equals(Arrays.asList(ORIGINAL + "$Picker;", "Lcom/google/protobuf/MessageLite;"))
                && m.getReturnType().equals("Lcom/google/protobuf/MessageLite;")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not find the queue playlist picker"));
        InstructionExtensions.INSTANCE.addInstructions(entry, 0, "invoke-static/range {p0 .. p1}, "
                + "Lapp/morphe/extension/youtube/queuesavetest/QueueSaveActions;->onPickerResponse(" + ORIGINAL
                + "$Picker;Lcom/google/protobuf/MessageLite;)Lcom/google/protobuf/MessageLite;\n"
                + "move-result-object p0\nreturn-object p0");

        try (var values = PatchFunctionsKt.resourceXml("res/values/strings.xml", false, null)) {
            values.getRoot().appendChild("string", text -> {
                text.setAttribute("name", "morphe_native_playlist_queue_no_save_destinations");
                text.setText("Can't save to existing playlists");
                return Unit.INSTANCE;
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Could not add the queue save message", ex);
        }
    }
}
