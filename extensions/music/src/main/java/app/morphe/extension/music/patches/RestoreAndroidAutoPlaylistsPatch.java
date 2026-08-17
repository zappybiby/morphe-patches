/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.util.Base64;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String YTM_COLLECTION_BROWSE_ID_PREFIX = "VL";
    private static final String COLLECTION_MEDIA_ID_PREFIX = "morphe:aa-collection:";
    private static final String PLAYLISTS_TITLE_RESOURCE = "library_playlists_shelf_title";

    private static final int SCHEMA_RESPONSIVE_RENDERER_CLASS_INDEX = 0;
    private static final int SCHEMA_MUSIC_SHELF_RENDERER_CLASS_INDEX = 1;
    private static final int SCHEMA_RESPONSIVE_RENDERER_ENDPOINT_FIELD_INDEX = 2;
    private static final int SCHEMA_MEDIA_ID_HELPER_CLASS_INDEX = 3;
    private static final int SCHEMA_MEDIA_ID_HELPER_METHOD_INDEX = 4;
    private static final int SCHEMA_BROWSE_ENDPOINT_CLASS_INDEX = 5;
    private static final int SCHEMA_BROWSE_ID_FIELD_INDEX = 6;
    private static final int SCHEMA_BROWSE_ID_SETTER_INDEX = 7;
    private static final int SCHEMA_CONTINUATION_SETTER_INDEX = 8;
    private static final int SCHEMA_MEDIA_ID_FIELD_PATH_INDEX = 9;
    private static final int SCHEMA_BROWSE_BUILDER_FACTORY_INDEX = 10;
    private static final int SCHEMA_BROWSE_REQUEST_METHOD_INDEX = 11;
    private static final int SCHEMA_CLIENT_DATA_SETTER_INDEX = 12;
    private static final int SCHEMA_RESULT_DELIVERY_METHOD_INDEX = 13;
    private static final int SCHEMA_RESULT_DELIVERY_PARAMETER_COUNT_INDEX = 14;
    private static final int SCHEMA_EXTENSION_MAP_CLASS_INDEX = 15;
    private static final int SCHEMA_EXTENSION_MAP_FIELD_INDEX = 16;
    private static final int SCHEMA_EXTENSION_ITERATOR_METHOD_INDEX = 17;
    private static final int SCHEMA_SIZE = SCHEMA_EXTENSION_ITERATOR_METHOD_INDEX + 1;

    // YTM 9.15/9.29/9.30/9.31 MediaItemInfo: field 3 is the endpoint, field 4 is
    // container type, and field 5 is client type. Library is 3; car clients are 10 and 13.
    private static final int MEDIA_ITEM_ENDPOINT_FIELD_NUMBER = 3;
    // YTM 9.29/9.30/9.31 estimate the first ten items and cap browse delivery at 419,840 bytes.
    // Optional player config (init URL, decoders, and device flags) adds about 1,840 characters,
    // or 3.7 KB in a Parcel, to each media ID. Removing it kept a 285-track test list below the cap.
    private static final int WATCH_ENDPOINT_EXTENSION_FIELD_NUMBER = 48_687_757;
    private static final int WATCH_ENDPOINT_VIDEO_ID_FIELD_NUMBER = 1;
    private static final int OPTIONAL_PLAYER_CONFIG_FIELD_NUMBER = 79_857_908;
    private static final int[] PLAYABLE_VIDEO_ID_FIELD_PATH = {
            MEDIA_ITEM_ENDPOINT_FIELD_NUMBER,
            WATCH_ENDPOINT_EXTENSION_FIELD_NUMBER,
            WATCH_ENDPOINT_VIDEO_ID_FIELD_NUMBER
    };
    private static final int[] OPTIONAL_PLAYER_CONFIG_FIELD_PATH = {
            MEDIA_ITEM_ENDPOINT_FIELD_NUMBER,
            WATCH_ENDPOINT_EXTENSION_FIELD_NUMBER,
            OPTIONAL_PLAYER_CONFIG_FIELD_NUMBER
    };
    private static final int MEDIA_ID_BASE64_OPTIONS =
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    private static final Executor REQUEST_EXECUTOR = Utils::runOnBackgroundThread;
    private static final ConcurrentHashMap<String, CompletableFuture<List<Object>>> IN_FLIGHT_LOADS =
            new ConcurrentHashMap<>();
    private static volatile Object authenticatedBrowseService;
    private static volatile String nativePlaylistsMediaId;
    private static volatile String configuredSchema;
    private static volatile String[] loadResultMediaIdFieldPath;
    private static volatile String browseIdSetterMethodName;
    private static volatile String continuationSetterMethodName;
    private static volatile String browseBuilderFactoryMethodName;
    private static volatile String browseRequestMethodName;
    private static volatile String clientDataSetterMethodName;
    private static volatile String resultDeliveryMethodName;
    private static volatile int resultDeliveryParameterCount;
    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /** Saves the fields and methods resolved for the installed YTM version. */
    public static synchronized void configure(String encodedSchema) {
        if (encodedSchema == null) return;
        if (encodedSchema.equals(configuredSchema)) return;
        String[] schemaValues = encodedSchema.split("\\|", -1);
        if (schemaValues.length != SCHEMA_SIZE) {
            Logger.printException(() -> "Android Auto playlist schema has " +
                    schemaValues.length + " values; expected " + SCHEMA_SIZE);
            return;
        }
        try {
            for (String schemaValue : schemaValues) {
                if (schemaValue.isEmpty()) throw new IllegalArgumentException("Empty schema value");
            }
            int deliveryParameterCount = Integer.parseInt(
                    schemaValues[SCHEMA_RESULT_DELIVERY_PARAMETER_COUNT_INDEX]);
            if (deliveryParameterCount < 1) {
                throw new IllegalArgumentException("Invalid delivery parameter count");
            }

            PlaylistPageMapper.configure(
                    schemaValues[SCHEMA_RESPONSIVE_RENDERER_CLASS_INDEX],
                    schemaValues[SCHEMA_MUSIC_SHELF_RENDERER_CLASS_INDEX],
                    schemaValues[SCHEMA_RESPONSIVE_RENDERER_ENDPOINT_FIELD_INDEX],
                    schemaValues[SCHEMA_MEDIA_ID_HELPER_CLASS_INDEX],
                    schemaValues[SCHEMA_MEDIA_ID_HELPER_METHOD_INDEX],
                    schemaValues[SCHEMA_BROWSE_ENDPOINT_CLASS_INDEX],
                    schemaValues[SCHEMA_BROWSE_ID_FIELD_INDEX],
                    schemaValues[SCHEMA_EXTENSION_MAP_CLASS_INDEX],
                    schemaValues[SCHEMA_EXTENSION_MAP_FIELD_INDEX],
                    schemaValues[SCHEMA_EXTENSION_ITERATOR_METHOD_INDEX]);
            loadResultMediaIdFieldPath = schemaValues[SCHEMA_MEDIA_ID_FIELD_PATH_INDEX].split(",");
            browseIdSetterMethodName = schemaValues[SCHEMA_BROWSE_ID_SETTER_INDEX];
            continuationSetterMethodName = schemaValues[SCHEMA_CONTINUATION_SETTER_INDEX];
            browseBuilderFactoryMethodName = schemaValues[SCHEMA_BROWSE_BUILDER_FACTORY_INDEX];
            browseRequestMethodName = schemaValues[SCHEMA_BROWSE_REQUEST_METHOD_INDEX];
            clientDataSetterMethodName = schemaValues[SCHEMA_CLIENT_DATA_SETTER_INDEX];
            resultDeliveryMethodName = schemaValues[SCHEMA_RESULT_DELIVERY_METHOD_INDEX];
            resultDeliveryParameterCount = deliveryParameterCount;
            configuredSchema = encodedSchema;
        } catch (Throwable error) {
            Logger.printException(
                    () -> "Could not configure Android Auto playlist schema", error);
        }
    }

    /** Saves the Browse service used by Android Auto and clears state when YTM replaces it. */
    public static void initialize(Object service) {
        if (service == null) return;
        if (authenticatedBrowseService != service) nativePlaylistsMediaId = null;
        authenticatedBrowseService = service;
        Logger.printDebug(() -> "Authenticated Browse service ready: " +
                service.getClass().getName());
    }

    public static boolean handleCollection(Object loadResult) {
        if (loadResult == null || authenticatedBrowseService == null) return false;

        if (isNativePlaylistNode(loadResult)) {
            loadAsync(
                    loadResult,
                    LIBRARY_BROWSE_ID,
                    () -> requestBrowse(LIBRARY_BROWSE_ID, null)
                            .thenApply(RestoreAndroidAutoPlaylistsPatch::mapLibrary));
            return true;
        }

        String mediaId = mediaId(loadResult);
        if (mediaId == null || !mediaId.startsWith(COLLECTION_MEDIA_ID_PREFIX)) return false;

        String browseId = mediaId.substring(COLLECTION_MEDIA_ID_PREFIX.length());
        loadAsync(loadResult, browseId, () -> loadCollection(browseId));
        return true;
    }

    /** Records the media ID for YTM's localized Playlists row and ignores every other row. */
    public static void rememberNativePlaylistsMediaId(
            String mediaId, CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE)
                .contentEquals(title)) return;
        nativePlaylistsMediaId = mediaId;
    }

    private static void loadAsync(
            Object loadResult,
            String requestKey,
            Supplier<CompletableFuture<List<Object>>> requestFactory) {
        CompletableFuture<List<Object>> newLoad = new CompletableFuture<>();
        CompletableFuture<List<Object>> sharedLoad = IN_FLIGHT_LOADS.putIfAbsent(
                requestKey, newLoad);
        if (sharedLoad == null) {
            sharedLoad = newLoad;
            try {
                requestFactory.get().whenComplete((items, error) -> {
                    if (error != null) {
                        Logger.printException(
                                () -> "Authenticated Browse request failed: " + requestKey, error);
                        items = Collections.emptyList();
                    }
                    newLoad.complete(items);
                    IN_FLIGHT_LOADS.remove(requestKey, newLoad);
                });
            } catch (Throwable error) {
                Logger.printException(
                        () -> "Authenticated Browse request failed: " + requestKey, error);
                newLoad.complete(Collections.emptyList());
                IN_FLIGHT_LOADS.remove(requestKey, newLoad);
            }
        }
        sharedLoad.thenAccept(items -> deliver(loadResult, items));
    }

    private static CompletableFuture<List<Object>> loadCollection(String browseId) {
        List<Object> items = new ArrayList<>();
        Set<String> mediaIds = new HashSet<>();
        Set<String> continuations = new HashSet<>();
        return loadCollectionPage(browseId, null, items, mediaIds, continuations);
    }

    private static CompletableFuture<List<Object>> loadCollectionPage(
            String browseId,
            String continuation,
            List<Object> items,
            Set<String> mediaIds,
            Set<String> continuations) {
        return requestBrowse(continuation == null ? browseId : null, continuation)
                .thenCompose(response -> {
                    String nextContinuation =
                            PlaylistPageMapper.appendPlaylistPage(response, items, mediaIds);
                    if (nextContinuation == null || nextContinuation.isEmpty()
                            || !continuations.add(nextContinuation)) {
                        return CompletableFuture.completedFuture(items);
                    }
                    return loadCollectionPage(
                            browseId, nextContinuation, items, mediaIds, continuations);
                });
    }

    private static CompletableFuture<Object> requestBrowse(
            String browseId, String continuation) {
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        try {
            Object service = authenticatedBrowseService;
            if (service == null) {
                throw new IllegalStateException("Browse service is not initialized");
            }

            Object builder = invoke(service, browseBuilderFactoryMethodName);
            if (continuation == null) {
                invoke(builder, browseIdSetterMethodName, browseId);
            } else {
                invoke(builder, continuationSetterMethodName, continuation);
            }
            invoke(builder, clientDataSetterMethodName, new byte[0]);

            ListenableFuture<?> browseRequest = (ListenableFuture<?>) invoke(
                    service, browseRequestMethodName, builder, REQUEST_EXECUTOR);
            browseRequest.addListener(() -> {
                try {
                    responseFuture.complete(browseRequest.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    responseFuture.completeExceptionally(error);
                } catch (Throwable error) {
                    responseFuture.completeExceptionally(error);
                }
            }, REQUEST_EXECUTOR);
        } catch (Throwable error) {
            responseFuture.completeExceptionally(error);
        }
        return responseFuture;
    }

    private static List<Object> mapLibrary(Object response) {
        LibraryState state = new LibraryState();
        try {
            // YTM 9.15/9.29/9.30/9.31: Library playlists are nested in responsive-renderer
            // protobuf extensions. Walk the response and map only those renderer messages.
            PlaylistPageMapper.walkObjectGraph(response, state.objectGraphState, value -> {
                if (!PlaylistPageMapper.isResponsiveRenderer(value)) return false;
                appendLibraryCollection(value, state);
                return true;
            });
        } catch (Throwable error) {
            Logger.printException(() -> "Could not map Library response", error);
        }
        Logger.printDebug(() -> "Mapped Library collections: " + state.items.size());
        return state.items;
    }

    private static void appendLibraryCollection(Object renderer, LibraryState state) {
        try {
            String browseId = PlaylistPageMapper.responsiveRendererBrowseId(renderer);
            if (browseId == null || !browseId.startsWith(YTM_COLLECTION_BROWSE_ID_PREFIX)) return;
            if (state.browseIds.contains(browseId)) return;

            String title = PlaylistPageMapper.responsiveRendererTitle(renderer);
            if (title.isEmpty()) return;
            String subtitle = PlaylistPageMapper.responsiveRendererSubtitle(renderer);
            Uri artwork = PlaylistPageMapper.responsiveRendererArtwork(renderer);
            Object item = PlaylistPageMapper.createBrowsableItem(
                    encodeCollectionMediaId(browseId), title, subtitle, artwork);
            if (state.browseIds.add(browseId)) state.items.add(item);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not map Library collection row", error);
        }
    }

    private static boolean isNativePlaylistNode(Object loadResult) {
        String mediaId = mediaId(loadResult);
        return mediaId != null && mediaId.equals(nativePlaylistsMediaId);
    }

    private static byte[] decodeMediaId(String mediaId) {
        try {
            return Base64.decode(mediaId, MEDIA_ID_BASE64_OPTIONS);
        } catch (RuntimeException ignored) {
            return new byte[0];
        }
    }

    static String removeOptionalPlayerConfigFromMediaId(String mediaId) {
        try {
            byte[] withoutPlayerConfig = removeOptionalPlayerConfig(decodeMediaId(mediaId), 0);
            return withoutPlayerConfig == null
                    ? mediaId
                    : Base64.encodeToString(withoutPlayerConfig, MEDIA_ID_BASE64_OPTIONS);
        } catch (RuntimeException ignored) {
            return mediaId;
        }
    }

    static boolean hasPlayableVideoId(String mediaId) {
        try {
            return hasNonEmptyFieldPath(decodeMediaId(mediaId), PLAYABLE_VIDEO_ID_FIELD_PATH, 0);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasNonEmptyFieldPath(
            byte[] message, int[] fieldPath, int fieldPathIndex) {
        CodedInputStream input = CodedInputStream.newInstance(message);
        try {
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int wireType = WireFormat.getTagWireType(tag);
                if (WireFormat.getTagFieldNumber(tag) != fieldPath[fieldPathIndex]
                        || wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    input.skipField(tag);
                    continue;
                }

                byte[] nestedMessage = input.readByteArray();
                if (fieldPathIndex == fieldPath.length - 1) {
                    return nestedMessage.length != 0;
                }
                if (hasNonEmptyFieldPath(nestedMessage, fieldPath, fieldPathIndex + 1)) return true;
            }
            return false;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid media ID", error);
        }
    }

    private static byte[] removeOptionalPlayerConfig(byte[] message, int fieldPathIndex) {
        CodedInputStream input = CodedInputStream.newInstance(message);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(message.length);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        try {
            boolean removed = false;
            while (!input.isAtEnd()) {
                int fieldStart = input.getTotalBytesRead();
                int tag = input.readTag();
                int tagEnd = input.getTotalBytesRead();
                int wireType = WireFormat.getTagWireType(tag);
                if (WireFormat.getTagFieldNumber(tag)
                        != OPTIONAL_PLAYER_CONFIG_FIELD_PATH[fieldPathIndex]) {
                    input.skipField(tag);
                    output.writeRawBytes(
                            message, fieldStart, input.getTotalBytesRead() - fieldStart);
                    continue;
                }

                if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    throw new IllegalArgumentException("Invalid player config field");
                }
                byte[] nestedMessage = input.readByteArray();
                if (fieldPathIndex == OPTIONAL_PLAYER_CONFIG_FIELD_PATH.length - 1) {
                    if (removed) throw new IllegalArgumentException("Repeated player config");
                    removed = true;
                    continue;
                }

                byte[] nested = removeOptionalPlayerConfig(nestedMessage, fieldPathIndex + 1);
                if (nested == null) {
                    output.writeRawBytes(
                            message, fieldStart, input.getTotalBytesRead() - fieldStart);
                    continue;
                }
                if (removed) throw new IllegalArgumentException("Repeated player config");

                output.writeRawBytes(message, fieldStart, tagEnd - fieldStart);
                output.writeUInt32NoTag(nested.length);
                output.writeRawBytes(nested);
                removed = true;
            }
            output.flush();
            return removed ? bytes.toByteArray() : null;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid media ID", error);
        }
    }

    private static void deliver(Object loadResult, List<Object> items) {
        try {
            invokeDelivery(loadResult, items);
            Logger.printDebug(() -> "Delivered browse items to Android Auto: " + items.size());
        } catch (Throwable error) {
            Logger.printException(() -> "Could not deliver browse items to Android Auto", error);
        }
    }

    static void invokeDelivery(Object loadResult, List<Object> items) throws Exception {
        // YTM 9.15/9.29: the one-argument wrapper supplied a null interaction context.
        // YTM 9.30/9.31: the wrapper is gone, so supply the same null here.
        Object[] arguments = new Object[resultDeliveryParameterCount];
        arguments[0] = items;
        invoke(loadResult, resultDeliveryMethodName, arguments);
    }

    static String encodeCollectionMediaId(String browseId) {
        return COLLECTION_MEDIA_ID_PREFIX + browseId;
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        for (Class<?> owner = target.getClass(); owner != null && owner != Object.class;
                owner = owner.getSuperclass()) {
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !parametersAccept(
                        method.getParameterTypes(), arguments)) continue;
                method.setAccessible(true);
                return method.invoke(target, arguments);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static boolean parametersAccept(Class<?>[] parameterTypes, Object[] arguments) {
        if (parameterTypes.length != arguments.length) return false;
        for (int i = 0; i < parameterTypes.length; i++) {
            Object argument = arguments[i];
            if (argument == null) {
                if (parameterTypes[i].isPrimitive()) return false;
            } else if (!parameterTypes[i].isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static String mediaId(Object loadResult) {
        try {
            Object value = PlaylistPageMapper.readFieldPath(
                    loadResult, loadResultMediaIdFieldPath);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class LibraryState {
        private final List<Object> items = new ArrayList<>();
        private final Set<String> browseIds = new HashSet<>();
        private final PlaylistPageMapper.ObjectGraphState objectGraphState =
                new PlaylistPageMapper.ObjectGraphState();
    }

}
