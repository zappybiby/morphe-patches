/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String PLAYLISTS_TITLE_RESOURCE = "library_playlists_shelf_title";
    private static final String YTM_COLLECTION_BROWSE_ID_PREFIX = "VL";
    private static final int RUNTIME_SCHEMA_VERSION = 2;
    private static final int RUNTIME_SCHEMA_VALUE_COUNT = 20;
    private static final long BROWSE_REQUEST_TIMEOUT_MILLISECONDS = 30_000;
    private static final int PLAYLIST_ARTWORK_SIZE_PX = 544;
    // YTM 9.15/9.29/9.30/9.31: extension 164480666 keeps thumbnails at c.c, with each
    // URL in c. Library URLs request 60-192 px; the same Google CDN URL returns 544 px.
    private static final String[] PLAYLIST_THUMBNAIL_LIST_FIELD_PATH = {"c", "c"};
    private static final String PLAYLIST_THUMBNAIL_URL_FIELD_NAME = "c";
    // YTM 9.15/9.29/9.30/9.31: text uses d for a direct value, c for its runs, and c
    // for each run's value.
    private static final String TEXT_DIRECT_VALUE_FIELD_NAME = "d";
    private static final String TEXT_RUNS_FIELD_NAME = "c";
    private static final String TEXT_RUN_VALUE_FIELD_NAME = "c";

    private static final Executor REQUEST_EXECUTOR = Utils::runOnBackgroundThread;
    private static final Set<String> NATIVE_PLAYLISTS_NODE_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();

    private static volatile Object authenticatedBrowseService;
    private static CompletableFuture<List<MediaBrowserCompat.MediaItem>> inFlightLibraryLoad;
    private static volatile RuntimeConfiguration runtimeConfiguration;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /** Saves the fields and methods resolved for the installed YTM version. */
    public static synchronized void configure(String encodedSchema) {
        RuntimeConfiguration current = runtimeConfiguration;
        if (current != null) {
            if (!encodedSchema.equals(current.schema.encodedValue)) {
                Logger.printException(
                        () -> "Could not configure Android Auto playlists",
                        new IllegalStateException("Runtime schema changed in the same process"));
            }
            return;
        }

        try {
            RuntimeSchema schema = RuntimeSchema.parse(encodedSchema);
            runtimeConfiguration = new RuntimeConfiguration(
                    schema,
                    schema.endpointMediaIdMethod.resolve(),
                    schema.browseIdSetterMethod.resolve(),
                    schema.browseBuilderFactoryMethod.resolve(),
                    schema.browseRequestMethod.resolve(),
                    schema.clientDataSetterMethod.resolve(),
                    schema.resultDeliveryMethod.resolve(),
                    resolveField(schema.extensionMapClassName, schema.extensionMapFieldName),
                    schema.extensionMapIteratorMethod.resolve());
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.printException(() -> "Could not configure Android Auto playlists", error);
        }
    }

    /** Saves the Browse service used by Android Auto and clears state when YTM replaces it. */
    public static void initialize(Object service) {
        try {
            if (runtimeConfiguration == null || service == null) return;
            synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
                if (authenticatedBrowseService != service) {
                    NATIVE_PLAYLISTS_NODE_MEDIA_IDS.clear();
                    inFlightLibraryLoad = null;
                }
                authenticatedBrowseService = service;
            }
            Logger.printDebug(() -> "Authenticated Browse service ready: " +
                    service.getClass().getName());
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not initialize Android Auto playlists", error);
        }
    }

    /**
     * Claims the remembered Playlists request. Once claimed, this method must deliver a result
     * because the injected caller returns without running YouTube Music's original loader.
     */
    public static boolean handlePlaylistsNode(Object loadResult) {
        try {
            if (!isReady() || !isNativePlaylistsNode(loadResult)) return false;
            loadPlayableLibraryPlaylists().thenAccept(items -> deliver(loadResult, items));
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.printException(() -> "Could not start Android Auto playlist request", error);
            return false;
        }
    }

    /** Records the media ID for YTM's localized Playlists row and ignores every other row. */
    public static void rememberNativePlaylistsMediaId(
            String mediaId, CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE)
                .contentEquals(title)) return;
        if (mediaId != null) NATIVE_PLAYLISTS_NODE_MEDIA_IDS.add(mediaId);
    }

    private static CompletableFuture<List<MediaBrowserCompat.MediaItem>>
            loadPlayableLibraryPlaylists() throws ReflectiveOperationException {
        synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
            if (inFlightLibraryLoad != null) return inFlightLibraryLoad;

            CompletableFuture<Object> response = requestLibrary();
            CompletableFuture<List<MediaBrowserCompat.MediaItem>> load = new CompletableFuture<>();
            inFlightLibraryLoad = load;
            // Only concurrent requests share work. A later open reloads the current Library.
            response
                    .thenApply(RestoreAndroidAutoPlaylistsPatch::extractPlayableLibraryPlaylists)
                    .whenComplete((items, error) -> completeLibraryLoad(load, items, error));
            return load;
        }
    }

    private static void completeLibraryLoad(
            CompletableFuture<List<MediaBrowserCompat.MediaItem>> load,
            List<MediaBrowserCompat.MediaItem> items,
            Throwable error) {
        if (error != null) {
            Logger.printException(() -> "Library Browse request failed", error);
            // The original loader cannot resume after this request is claimed. Completing with an
            // empty list prevents Android Auto from waiting indefinitely for a callback.
            items = Collections.emptyList();
        }
        load.complete(items);
        synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
            if (inFlightLibraryLoad == load) inFlightLibraryLoad = null;
        }
    }

    private static CompletableFuture<Object> requestLibrary() throws ReflectiveOperationException {
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        RuntimeConfiguration configuration = runtimeConfiguration;
        Object service = authenticatedBrowseService;
        Object builder = configuration.browseBuilderFactoryMethod.invoke(service);
        configuration.browseIdSetterMethod.invoke(builder, LIBRARY_BROWSE_ID);
        // The builder requires a non-null byte array; this Library root has no client-data token.
        configuration.clientDataSetterMethod.invoke(builder, new byte[0]);

        ListenableFuture<?> browseRequest = (ListenableFuture<?>)
                configuration.browseRequestMethod.invoke(service, builder, REQUEST_EXECUTOR);
        browseRequest.addListener(() -> {
            try {
                responseFuture.complete(browseRequest.get());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                responseFuture.completeExceptionally(error);
            } catch (ExecutionException | CancellationException error) {
                responseFuture.completeExceptionally(error);
            }
        }, REQUEST_EXECUTOR);
        // If YTM's future stalls, finish the claimed callback instead of leaving Android Auto
        // waiting.
        Utils.runOnMainThreadDelayed(() -> {
            TimeoutException error = new TimeoutException("Library Browse request timed out");
            if (responseFuture.completeExceptionally(error)) browseRequest.cancel(true);
        }, BROWSE_REQUEST_TIMEOUT_MILLISECONDS);
        return responseFuture;
    }

    private static List<MediaBrowserCompat.MediaItem> extractPlayableLibraryPlaylists(
            Object response) {
        LibraryState state = new LibraryState();
        try {
            // YTM 9.15/9.29/9.30/9.31: a saved playlist pairs a VL Browse ID with a playback
            // endpoint for the same playlist. Find those responsive renderers in the Library response.
            findLibraryPlaylists(response, state);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not map Library response", error);
        }
        Logger.printDebug(() -> "Mapped playable Library playlists: " + state.items.size());
        return state.items;
    }

    private static void appendLibraryPlaylist(
            Object renderer, LibraryState state) throws ReflectiveOperationException {
        PlaylistMediaIds mediaIds = responsiveRendererMediaIds(renderer);
        if (mediaIds == null || state.browseIds.contains(mediaIds.browseId)) return;

        String title = responsiveRendererTitle(renderer);
        if (title.isEmpty()) return;
        state.browseIds.add(mediaIds.browseId);
        state.items.add(createPlayableItem(
                mediaIds.playableMediaId,
                title,
                optionalResponsiveRendererSubtitle(renderer),
                optionalResponsiveRendererArtwork(renderer)));
    }

    private static PlaylistMediaIds responsiveRendererMediaIds(
            Object renderer) throws ReflectiveOperationException {
        RuntimeSchema schema = runtimeConfiguration.schema;
        List<Object> endpoints = new ArrayList<>(schema.responsiveRendererEndpointFieldNames.length);
        String playlistBrowseId = null;
        for (String fieldName : schema.responsiveRendererEndpointFieldNames) {
            Object endpoint = readField(renderer, fieldName);
            if (endpoint == null) continue;
            endpoints.add(endpoint);
            String browseId = findBrowseId(endpoint);
            if (browseId == null || !browseId.startsWith(YTM_COLLECTION_BROWSE_ID_PREFIX)) continue;
            if (playlistBrowseId != null && !playlistBrowseId.equals(browseId)) return null;
            playlistBrowseId = browseId;
        }
        if (playlistBrowseId == null) return null;

        String playlistId = playlistBrowseId.substring(YTM_COLLECTION_BROWSE_ID_PREFIX.length());
        String playableMediaId = null;
        for (Object endpoint : endpoints) {
            if (!endpointContainsPlaylistId(endpoint, playlistId)) continue;
            String mediaId = mediaIdForEndpoint(endpoint);
            if (mediaId == null || mediaId.isEmpty()) continue;
            if (playableMediaId != null && !playableMediaId.equals(mediaId)) return null;
            playableMediaId = mediaId;
        }
        return playableMediaId == null
                ? null
                : new PlaylistMediaIds(playlistBrowseId, playableMediaId);
    }

    // YTM 9.15/9.29/9.30/9.31: the playlist endpoint extension contains the playlist ID.
    // Match its value instead of relying on the generated String field name.
    private static boolean endpointContainsPlaylistId(
            Object endpoint, String playlistId) throws ReflectiveOperationException {
        Object extension = findExtension(
                endpoint, runtimeConfiguration.schema.playlistEndpointClassName);
        if (extension == null) return false;
        for (Class<?> owner = extension.getClass(); owner != null && owner != Object.class;
                owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                field.setAccessible(true);
                if (playlistId.equals(field.get(extension))) return true;
            }
        }
        return false;
    }

    // YTM 9.15/9.29/9.30/9.31: the endpoint-to-media-ID helper keeps the same behavior but
    // changes names. The bytecode patch supplies the helper resolved for the current APK.
    private static String mediaIdForEndpoint(
            Object endpoint) throws ReflectiveOperationException {
        Object value = runtimeConfiguration.endpointMediaIdMethod.invoke(null, endpoint);
        return (String) value;
    }

    private static boolean isResponsiveRenderer(Object value) {
        return value.getClass().getName().equals(
                runtimeConfiguration.schema.responsiveRendererClassName);
    }

    private static String responsiveRendererTitle(
            Object renderer) throws ReflectiveOperationException {
        return renderText(readField(
                renderer, runtimeConfiguration.schema.responsiveRendererTitleFieldName));
    }

    private static String optionalResponsiveRendererSubtitle(Object renderer) {
        try {
            return renderText(readField(
                    renderer, runtimeConfiguration.schema.responsiveRendererSubtitleFieldName));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }

    private static Uri optionalResponsiveRendererArtwork(Object renderer) {
        try {
            RuntimeSchema schema = runtimeConfiguration.schema;
            Object artwork = readField(renderer, schema.responsiveRendererArtworkFieldName);
            Object thumbnailRenderer = findExtension(
                    artwork, schema.playlistThumbnailRendererClassName);
            Iterable<?> thumbnails = (Iterable<?>) readFieldPath(
                    thumbnailRenderer, PLAYLIST_THUMBNAIL_LIST_FIELD_PATH);
            for (Object thumbnail : thumbnails) {
                String candidate = (String) readField(
                        thumbnail, PLAYLIST_THUMBNAIL_URL_FIELD_NAME);
                if (candidate.startsWith("https://")) return playlistArtworkUri(candidate);
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String findBrowseId(
            Object endpoint) throws ReflectiveOperationException {
        RuntimeSchema schema = runtimeConfiguration.schema;
        Object browseEndpoint = findExtension(endpoint, schema.browseEndpointClassName);
        if (browseEndpoint == null) return null;
        String browseId = (String) readField(browseEndpoint, schema.browseIdFieldName);
        return browseId.isEmpty() ? null : browseId;
    }

    private static MediaBrowserCompat.MediaItem createPlayableItem(
            String mediaId, String title, String subtitle, Uri iconUri) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitle, null, null, iconUri, null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static Uri playlistArtworkUri(String url) {
        Uri uri = Uri.parse(url);
        if (!"yt3.googleusercontent.com".equals(uri.getHost())) return uri;
        int optionsStart = url.lastIndexOf('=');
        return optionsStart < 0 ? uri : Uri.parse(
                url.substring(0, optionsStart + 1) + "s" + PLAYLIST_ARTWORK_SIZE_PX);
    }

    private static String renderText(Object text) throws ReflectiveOperationException {
        if (text == null) return "";

        String direct = (String) readField(text, TEXT_DIRECT_VALUE_FIELD_NAME);
        if (!direct.isEmpty()) return direct;

        StringBuilder combinedText = new StringBuilder();
        for (Object run : (Iterable<?>) readField(text, TEXT_RUNS_FIELD_NAME)) {
            combinedText.append((String) readField(run, TEXT_RUN_VALUE_FIELD_NAME));
        }
        return combinedText.toString();
    }

    private static Object readField(
            Object instance, String name) throws ReflectiveOperationException {
        if (instance == null) return null;
        for (Class<?> owner = instance.getClass(); owner != null && owner != Object.class;
                owner = owner.getSuperclass()) {
            try {
                Field declaredField = owner.getDeclaredField(name);
                declaredField.setAccessible(true);
                return declaredField.get(instance);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(instance.getClass().getName() + "." + name);
    }

    private static Field resolveField(
            String ownerClassName, String name) throws ReflectiveOperationException {
        ClassLoader classLoader = RestoreAndroidAutoPlaylistsPatch.class.getClassLoader();
        Field field = Class.forName(ownerClassName, false, classLoader).getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object readFieldPath(
            Object instance, String[] fieldNames) throws ReflectiveOperationException {
        Object value = instance;
        for (String fieldName : fieldNames) value = readField(value, fieldName);
        return value;
    }

    private static Iterator<?> extensionEntries(
            Object message) throws ReflectiveOperationException {
        // These accessors are resolved while patching, so runtime does not search generated
        // protobuf classes on every Browse response.
        RuntimeConfiguration configuration = runtimeConfiguration;
        if (!configuration.extensionMapField.getDeclaringClass().isInstance(message)) return null;
        Object extensionMap = configuration.extensionMapField.get(message);
        return (Iterator<?>) configuration.extensionMapIteratorMethod.invoke(extensionMap);
    }

    private static Object findExtension(
            Object message, String className) throws ReflectiveOperationException {
        Iterator<?> entries = extensionEntries(message);
        if (entries == null) return null;
        while (entries.hasNext()) {
            Object extension = ((Map.Entry<?, ?>) entries.next()).getValue();
            if (extension.getClass().getName().equals(className)) {
                return extension;
            }
        }
        return null;
    }

    // YTM 9.15/9.29/9.30/9.31: renderer messages can appear in generated fields, iterables, or
    // protobuf extensions. Breadth-first traversal finds renderers through all three shapes.
    private static void findLibraryPlaylists(
            Object value, LibraryState state) throws ReflectiveOperationException {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        enqueue(value, state, pending);

        while (!pending.isEmpty()) {
            Object current = pending.removeFirst();
            if (isResponsiveRenderer(current)) {
                try {
                    appendLibraryPlaylist(current, state);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Logger.printException(() -> "Library playlist skipped", error);
                }
                continue;
            }

            Class<?> valueClass = current.getClass();
            if (current instanceof CharSequence || current instanceof Number ||
                    current instanceof Boolean || valueClass.isEnum()) {
                continue;
            }
            if (current instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) current) enqueue(item, state, pending);
                continue;
            }

            if (!isObfuscatedYtmClass(valueClass)) continue;
            Iterator<?> entries = extensionEntries(current);
            if (entries != null) {
                while (entries.hasNext()) {
                    enqueue(((Map.Entry<?, ?>) entries.next()).getValue(), state, pending);
                }
            }
            for (Class<?> owner = valueClass; owner != null && owner != Object.class;
                    owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) ||
                            field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        enqueue(field.get(current), state, pending);
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
        }
    }

    private static boolean isObfuscatedYtmClass(Class<?> valueClass) {
        // YTM's generated response classes use the default package. This boundary keeps traversal
        // out of Android, Java, and support-library object graphs.
        return valueClass.getName().indexOf('.') < 0;
    }

    private static void enqueue(
            Object value, LibraryState state, ArrayDeque<Object> pending) {
        if (value != null && state.seen.add(value)) pending.addLast(value);
    }

    private static boolean isNativePlaylistsNode(Object loadResult) {
        String mediaId = mediaId(loadResult);
        return mediaId != null && NATIVE_PLAYLISTS_NODE_MEDIA_IDS.contains(mediaId);
    }

    private static void deliver(
            Object loadResult, List<MediaBrowserCompat.MediaItem> items) {
        try {
            invokeDelivery(loadResult, items);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", error);
        }
    }

    static void invokeDelivery(
            Object loadResult, List<MediaBrowserCompat.MediaItem> items)
            throws ReflectiveOperationException {
        // YTM 9.15/9.29: the one-argument wrapper supplied a null interaction context.
        // YTM 9.30/9.31: the wrapper is gone, so supply the same null here.
        Method deliveryMethod = runtimeConfiguration.resultDeliveryMethod;
        Object[] arguments = new Object[deliveryMethod.getParameterCount()];
        arguments[0] = items;
        deliveryMethod.invoke(loadResult, arguments);
    }

    private static boolean isReady() {
        return runtimeConfiguration != null && authenticatedBrowseService != null;
    }

    private static String mediaId(Object loadResult) {
        try {
            return (String) readFieldPath(
                    loadResult, runtimeConfiguration.schema.loadResultMediaIdFieldPath);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    // Field order must match RuntimeSchema.encode() in the Kotlin patch. Pipes separate values;
    // commas separate lists, and hashes separate parts of a method descriptor.
    private static final class RuntimeSchema {
        private final String encodedValue;
        private final String responsiveRendererClassName;
        private final String playlistEndpointClassName;
        private final String[] responsiveRendererEndpointFieldNames;
        private final RuntimeMethodSchema endpointMediaIdMethod;
        private final String browseEndpointClassName;
        private final String browseIdFieldName;
        private final RuntimeMethodSchema browseIdSetterMethod;
        private final String[] loadResultMediaIdFieldPath;
        private final RuntimeMethodSchema browseBuilderFactoryMethod;
        private final RuntimeMethodSchema browseRequestMethod;
        private final RuntimeMethodSchema clientDataSetterMethod;
        private final RuntimeMethodSchema resultDeliveryMethod;
        private final String responsiveRendererArtworkFieldName;
        private final String playlistThumbnailRendererClassName;
        private final String responsiveRendererTitleFieldName;
        private final String responsiveRendererSubtitleFieldName;
        private final String extensionMapClassName;
        private final String extensionMapFieldName;
        private final RuntimeMethodSchema extensionMapIteratorMethod;

        private RuntimeSchema(String encodedValue, String[] values) {
            this.encodedValue = encodedValue;
            int index = 1;
            responsiveRendererClassName = values[index++];
            playlistEndpointClassName = values[index++];
            responsiveRendererEndpointFieldNames = splitList(values[index++]);
            endpointMediaIdMethod = RuntimeMethodSchema.parse(values[index++]);
            browseEndpointClassName = values[index++];
            browseIdFieldName = values[index++];
            browseIdSetterMethod = RuntimeMethodSchema.parse(values[index++]);
            loadResultMediaIdFieldPath = splitList(values[index++]);
            browseBuilderFactoryMethod = RuntimeMethodSchema.parse(values[index++]);
            browseRequestMethod = RuntimeMethodSchema.parse(values[index++]);
            clientDataSetterMethod = RuntimeMethodSchema.parse(values[index++]);
            resultDeliveryMethod = RuntimeMethodSchema.parse(values[index++]);
            responsiveRendererArtworkFieldName = values[index++];
            playlistThumbnailRendererClassName = values[index++];
            responsiveRendererTitleFieldName = values[index++];
            responsiveRendererSubtitleFieldName = values[index++];
            extensionMapClassName = values[index++];
            extensionMapFieldName = values[index++];
            extensionMapIteratorMethod = RuntimeMethodSchema.parse(values[index]);

            if (responsiveRendererEndpointFieldNames.length != 2) {
                throw new IllegalArgumentException(
                        "Runtime schema must contain exactly two renderer endpoint fields");
            }
        }

        private static RuntimeSchema parse(String encodedSchema) {
            String[] values = encodedSchema.split("\\|", -1);
            if (values.length != RUNTIME_SCHEMA_VALUE_COUNT) {
                throw new IllegalArgumentException(
                        "Runtime schema has " + values.length + " values; expected " +
                                RUNTIME_SCHEMA_VALUE_COUNT);
            }
            int version = Integer.parseInt(values[0]);
            if (version != RUNTIME_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported runtime schema version " + version);
            }
            return new RuntimeSchema(encodedSchema, values);
        }

        private static String[] splitList(String value) {
            return value.split(",");
        }
    }

    private static final class RuntimeMethodSchema {
        private final String ownerClassName;
        private final String name;
        private final String descriptor;

        private RuntimeMethodSchema(String[] values) {
            ownerClassName = values[0];
            name = values[1];
            descriptor = values[2];
        }

        private static RuntimeMethodSchema parse(String encodedMethod) {
            String[] values = encodedMethod.split("#", -1);
            if (values.length != 3 || values[0].isEmpty() || values[1].isEmpty() ||
                    values[2].isEmpty()) {
                throw new IllegalArgumentException("Invalid runtime method descriptor");
            }
            return new RuntimeMethodSchema(values);
        }

        private Method resolve() throws ReflectiveOperationException {
            ClassLoader classLoader = RestoreAndroidAutoPlaylistsPatch.class.getClassLoader();
            Class<?> owner = Class.forName(ownerClassName, false, classLoader);
            MethodType methodType = MethodType.fromMethodDescriptorString(descriptor, classLoader);
            Method method = owner.getDeclaredMethod(name, methodType.parameterArray());
            if (method.getReturnType() != methodType.returnType()) {
                throw new NoSuchMethodException("Configured method has a different return type");
            }
            method.setAccessible(true);
            return method;
        }
    }

    private static final class RuntimeConfiguration {
        private final RuntimeSchema schema;
        private final Method endpointMediaIdMethod;
        private final Method browseIdSetterMethod;
        private final Method browseBuilderFactoryMethod;
        private final Method browseRequestMethod;
        private final Method clientDataSetterMethod;
        private final Method resultDeliveryMethod;
        private final Field extensionMapField;
        private final Method extensionMapIteratorMethod;

        private RuntimeConfiguration(
                RuntimeSchema schema,
                Method endpointMediaIdMethod,
                Method browseIdSetterMethod,
                Method browseBuilderFactoryMethod,
                Method browseRequestMethod,
                Method clientDataSetterMethod,
                Method resultDeliveryMethod,
                Field extensionMapField,
                Method extensionMapIteratorMethod) {
            this.schema = schema;
            this.endpointMediaIdMethod = endpointMediaIdMethod;
            this.browseIdSetterMethod = browseIdSetterMethod;
            this.browseBuilderFactoryMethod = browseBuilderFactoryMethod;
            this.browseRequestMethod = browseRequestMethod;
            this.clientDataSetterMethod = clientDataSetterMethod;
            this.resultDeliveryMethod = resultDeliveryMethod;
            this.extensionMapField = extensionMapField;
            this.extensionMapIteratorMethod = extensionMapIteratorMethod;
        }
    }

    private static final class PlaylistMediaIds {
        private final String browseId;
        private final String playableMediaId;

        private PlaylistMediaIds(String browseId, String playableMediaId) {
            this.browseId = browseId;
            this.playableMediaId = playableMediaId;
        }
    }

    private static final class LibraryState {
        private final List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        private final Set<String> browseIds = new HashSet<>();
        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    }

}
