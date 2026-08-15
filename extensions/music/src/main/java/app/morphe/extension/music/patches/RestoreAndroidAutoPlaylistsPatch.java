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
import android.util.Base64;

import com.google.common.util.concurrent.ListenableFuture;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String YTM_COLLECTION_BROWSE_ID_PREFIX = "VL";

    private static final int SCHEMA_RESPONSIVE_RENDERER_CLASS_INDEX = 0;
    private static final int SCHEMA_PLAYLIST_ENDPOINT_CLASS_INDEX = 1;
    private static final int SCHEMA_RESPONSIVE_RENDERER_BROWSE_ENDPOINT_FIELD_INDEX = 2;
    private static final int SCHEMA_RESPONSIVE_RENDERER_PLAY_ENDPOINT_FIELD_INDEX = 3;
    private static final int SCHEMA_MEDIA_ID_HELPER_CLASS_INDEX = 4;
    private static final int SCHEMA_MEDIA_ID_HELPER_METHOD_INDEX = 5;
    private static final int SCHEMA_BROWSE_ENDPOINT_CLASS_INDEX = 6;
    private static final int SCHEMA_BROWSE_ID_FIELD_INDEX = 7;
    private static final int SCHEMA_BROWSE_ID_SETTER_INDEX = 8;
    private static final int SCHEMA_MEDIA_ID_FIELD_PATH_INDEX = 9;
    private static final int SCHEMA_BROWSE_BUILDER_FACTORY_INDEX = 10;
    private static final int SCHEMA_BROWSE_REQUEST_METHOD_INDEX = 11;
    private static final int SCHEMA_CLIENT_DATA_SETTER_INDEX = 12;
    private static final int SCHEMA_RESULT_DELIVERY_METHOD_INDEX = 13;
    private static final int SCHEMA_RESULT_DELIVERY_PARAMETER_COUNT_INDEX = 14;
    private static final int SCHEMA_MEDIA_ITEM_DESCRIPTION_FIELD_INDEX = 15;
    private static final int SCHEMA_DESCRIPTION_MEDIA_ID_FIELD_INDEX = 16;
    private static final int SCHEMA_DESCRIPTION_TITLE_FIELD_INDEX = 17;
    private static final int SCHEMA_PLAYLIST_TITLE_RESOURCE_ID_INDEX = 18;
    private static final int SCHEMA_SIZE = SCHEMA_PLAYLIST_TITLE_RESOURCE_ID_INDEX + 1;

    private static final String RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME = "c";
    private static final String RESPONSIVE_RENDERER_TITLE_FIELD_NAME = "g";
    private static final String RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME = "h";
    private static final String TEXT_RUNS_FIELD_NAME = "c";
    private static final String TEXT_DIRECT_VALUE_FIELD_NAME = "d";
    private static final String TEXT_RUN_VALUE_FIELD_NAME = "c";
    private static final String EXTENSION_MAP_FIELD_NAME = "j";
    private static final String EXTENSION_ITERATOR_METHOD_NAME = "e";
    // MediaItemInfo protobuf
    private static final int MEDIA_ITEM_CONTAINER_TYPE_FIELD_NUMBER = 4;
    private static final int MEDIA_ITEM_CLIENT_TYPE_FIELD_NUMBER = 5;
    private static final long CONTAINER_TYPE_DEFAULT = 0;
    private static final long CONTAINER_TYPE_LIBRARY = 3;
    private static final long CLIENT_TYPE_ANDROID_AUTO = 10;
    private static final long CLIENT_TYPE_ANDROID_AUTOMOTIVE = 13;
    private static final long UNSUPPORTED_CAR_CLIENT_TYPE = 0;
    private static final int PROTOBUF_WIRE_VARINT = 0;
    private static final int PROTOBUF_WIRE_FIXED_64 = 1;
    private static final int PROTOBUF_WIRE_LENGTH_DELIMITED = 2;
    private static final int PROTOBUF_WIRE_FIXED_32 = 5;
    private static final int PROTOBUF_TAG_FIELD_SHIFT = 3;
    private static final int PROTOBUF_TAG_WIRE_TYPE_MASK = 0x7;
    private static final int VARINT_PAYLOAD_BITS = 7;
    private static final int VARINT_PAYLOAD_MASK = 0x7f;
    private static final int VARINT_CONTINUATION_BIT = 0x80;
    private static final int UNSIGNED_BYTE_MASK = 0xff;
    private static final int MEDIA_ID_BASE64_OPTIONS =
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    private static final Executor REQUEST_EXECUTOR = Utils::runOnBackgroundThread;
    private static final ConcurrentHashMap<Long, String> NATIVE_PLAYLIST_NODE_MEDIA_IDS =
            new ConcurrentHashMap<>();

    private static volatile Object authenticatedBrowseService;
    private static CompletableFuture<List<Object>> inFlightLibraryLoad;
    private static volatile String responsiveRendererClassName;
    private static volatile String playlistEndpointClassName;
    private static volatile String responsiveRendererBrowseEndpointFieldName;
    private static volatile String responsiveRendererPlayEndpointFieldName;
    private static volatile String mediaIdHelperClassName;
    private static volatile String mediaIdHelperMethodName;
    private static volatile String browseEndpointClassName;
    private static volatile String browseIdFieldName;
    private static volatile String[] loadResultMediaIdFieldPath;
    private static volatile String browseIdSetterMethodName;
    private static volatile String browseBuilderFactoryMethodName;
    private static volatile String browseRequestMethodName;
    private static volatile String clientDataSetterMethodName;
    private static volatile String resultDeliveryMethodName;
    private static volatile int resultDeliveryParameterCount;
    private static volatile String mediaItemDescriptionFieldName;
    private static volatile String descriptionMediaIdFieldName;
    private static volatile String descriptionTitleFieldName;
    private static volatile int playlistTitleResourceId;
    private static volatile Method endpointMediaIdMethod;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    public static void configure(String encodedSchema) {
        if (encodedSchema == null) return;
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
            int titleResourceId = Integer.parseInt(
                    schemaValues[SCHEMA_PLAYLIST_TITLE_RESOURCE_ID_INDEX]);
            if (deliveryParameterCount < 1) {
                throw new IllegalArgumentException("Invalid delivery parameter count");
            }
            if (titleResourceId < 1) throw new IllegalArgumentException("Invalid title resource");

            responsiveRendererClassName =
                    schemaValues[SCHEMA_RESPONSIVE_RENDERER_CLASS_INDEX];
            playlistEndpointClassName = schemaValues[SCHEMA_PLAYLIST_ENDPOINT_CLASS_INDEX];
            responsiveRendererBrowseEndpointFieldName =
                    schemaValues[SCHEMA_RESPONSIVE_RENDERER_BROWSE_ENDPOINT_FIELD_INDEX];
            responsiveRendererPlayEndpointFieldName =
                    schemaValues[SCHEMA_RESPONSIVE_RENDERER_PLAY_ENDPOINT_FIELD_INDEX];
            mediaIdHelperClassName = schemaValues[SCHEMA_MEDIA_ID_HELPER_CLASS_INDEX];
            mediaIdHelperMethodName = schemaValues[SCHEMA_MEDIA_ID_HELPER_METHOD_INDEX];
            browseEndpointClassName = schemaValues[SCHEMA_BROWSE_ENDPOINT_CLASS_INDEX];
            browseIdFieldName = schemaValues[SCHEMA_BROWSE_ID_FIELD_INDEX];
            browseIdSetterMethodName = schemaValues[SCHEMA_BROWSE_ID_SETTER_INDEX];
            loadResultMediaIdFieldPath =
                    schemaValues[SCHEMA_MEDIA_ID_FIELD_PATH_INDEX].split(",");
            browseBuilderFactoryMethodName =
                    schemaValues[SCHEMA_BROWSE_BUILDER_FACTORY_INDEX];
            browseRequestMethodName = schemaValues[SCHEMA_BROWSE_REQUEST_METHOD_INDEX];
            clientDataSetterMethodName = schemaValues[SCHEMA_CLIENT_DATA_SETTER_INDEX];
            resultDeliveryMethodName = schemaValues[SCHEMA_RESULT_DELIVERY_METHOD_INDEX];
            resultDeliveryParameterCount = deliveryParameterCount;
            mediaItemDescriptionFieldName =
                    schemaValues[SCHEMA_MEDIA_ITEM_DESCRIPTION_FIELD_INDEX];
            descriptionMediaIdFieldName =
                    schemaValues[SCHEMA_DESCRIPTION_MEDIA_ID_FIELD_INDEX];
            descriptionTitleFieldName =
                    schemaValues[SCHEMA_DESCRIPTION_TITLE_FIELD_INDEX];
            playlistTitleResourceId = titleResourceId;
            endpointMediaIdMethod = null;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not configure Android Auto playlists", error);
        }
    }

    public static void initialize(Object service) {
        if (service == null) return;
        synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
            if (authenticatedBrowseService != service) {
                NATIVE_PLAYLIST_NODE_MEDIA_IDS.clear();
                inFlightLibraryLoad = null;
            }
            authenticatedBrowseService = service;
        }
        Logger.printDebug(() -> "Authenticated Browse service ready: " +
                service.getClass().getName());
    }

    public static boolean handlePlaylistNode(Object loadResult) {
        if (loadResult == null || authenticatedBrowseService == null ||
                !isNativePlaylistNode(loadResult)) {
            return false;
        }
        loadLibrary().thenAccept(items -> deliver(loadResult, items));
        return true;
    }

    public static void rememberNativePlaylistNode(Object loadResult, List<?> items) {
        if (loadResult == null || authenticatedBrowseService == null || items == null ||
                items.isEmpty()) {
            return;
        }
        String libraryMediaId = mediaId(loadResult);
        if (libraryMediaId == null) return;
        long clientType = carClientTypeForContainer(
                decodeMediaId(libraryMediaId), CONTAINER_TYPE_LIBRARY);
        if (clientType == UNSUPPORTED_CAR_CLIENT_TYPE) return;

        String playlistNodeMediaId = findNativePlaylistsMediaId(items, clientType);
        if (playlistNodeMediaId == null) {
            NATIVE_PLAYLIST_NODE_MEDIA_IDS.remove(clientType);
        } else {
            NATIVE_PLAYLIST_NODE_MEDIA_IDS.put(clientType, playlistNodeMediaId);
            Logger.printDebug(() -> "Matched Playlists among " +
                    items.size() + " Library items");
        }
    }

    private static String findNativePlaylistsMediaId(List<?> items, long clientType) {
        String playlistTitle;
        try {
            playlistTitle = Utils.getContext().getString(playlistTitleResourceId);
        } catch (Throwable error) {
            return null;
        }

        String uniquePlaylistMediaId = null;
        for (Object item : items) {
            try {
                Object description = readField(item, mediaItemDescriptionFieldName);
                if (description == null) continue;

                Object title = readField(description, descriptionTitleFieldName);
                if (!(title instanceof CharSequence) ||
                        !playlistTitle.contentEquals((CharSequence) title)) continue;

                Object mediaId = readField(description, descriptionMediaIdFieldName);
                if (!(mediaId instanceof String)) continue;
                String candidateMediaId = (String) mediaId;
                if (carClientTypeForContainer(
                        decodeMediaId(candidateMediaId), CONTAINER_TYPE_DEFAULT) != clientType) {
                    continue;
                }
                if (uniquePlaylistMediaId != null &&
                        !uniquePlaylistMediaId.equals(candidateMediaId)) return null;
                uniquePlaylistMediaId = candidateMediaId;
            } catch (Exception ignored) {
            }
        }
        return uniquePlaylistMediaId;
    }

    private static CompletableFuture<List<Object>> loadLibrary() {
        synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
            if (inFlightLibraryLoad != null) return inFlightLibraryLoad;

            CompletableFuture<List<Object>> load = new CompletableFuture<>();
            inFlightLibraryLoad = load;
            requestLibrary().thenApply(RestoreAndroidAutoPlaylistsPatch::mapLibrary)
                    .whenComplete((items, error) -> completeLibraryLoad(load, items, error));
            return load;
        }
    }

    private static void completeLibraryLoad(
            CompletableFuture<List<Object>> load, List<Object> items, Throwable error) {
        if (error != null) {
            Logger.printException(() -> "Library Browse request failed", error);
            items = Collections.emptyList();
        }
        load.complete(items);
        synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
            if (inFlightLibraryLoad == load) inFlightLibraryLoad = null;
        }
    }

    private static CompletableFuture<Object> requestLibrary() {
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        try {
            Object service = authenticatedBrowseService;
            if (service == null) throw new IllegalStateException("Browse service is not initialized");

            Object builder = invoke(service, browseBuilderFactoryMethodName);
            invoke(builder, browseIdSetterMethodName, LIBRARY_BROWSE_ID);
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
            walkObjectGraph(response, state.objectGraphState, value -> {
                if (!isResponsiveRenderer(value)) return false;
                appendLibraryPlaylist(value, state);
                return true;
            });
        } catch (Throwable error) {
            Logger.printException(() -> "Could not map Library response", error);
        }
        Logger.printDebug(() -> "Mapped playable Library playlists: " + state.items.size());
        return state.items;
    }

    private static void appendLibraryPlaylist(Object renderer, LibraryState state) {
        try {
            Object browseEndpoint = readField(renderer, responsiveRendererBrowseEndpointFieldName);
            if (browseEndpoint == null) return;
            String browseId = findBrowseId(browseEndpoint);
            if (browseId == null || !browseId.startsWith(YTM_COLLECTION_BROWSE_ID_PREFIX) ||
                    state.browseIds.contains(browseId)) {
                return;
            }
            String playlistId = browseId.substring(YTM_COLLECTION_BROWSE_ID_PREFIX.length());
            Object playEndpoint = readField(renderer, responsiveRendererPlayEndpointFieldName);
            if (playlistId.isEmpty() || playEndpoint == null ||
                    !endpointContainsPlaylistId(playEndpoint, playlistId)) return;
            String playlistMediaId = mediaIdForEndpoint(playEndpoint);
            if (playlistMediaId == null || playlistMediaId.isEmpty()) return;

            String title = responsiveRendererTitle(renderer);
            if (title.isEmpty()) return;
            Object item = createPlayableItem(
                    playlistMediaId,
                    title,
                    responsiveRendererSubtitle(renderer),
                    responsiveRendererArtwork(renderer));
            state.browseIds.add(browseId);
            state.items.add(item);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not map Library playlist", error);
        }
    }

    private static boolean endpointContainsPlaylistId(Object endpoint, String playlistId) {
        Object extension = findExtension(endpoint, playlistEndpointClassName);
        if (extension == null) return false;
        for (Class<?> owner = extension.getClass(); owner != null && owner != Object.class;
                owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (playlistId.equals(field.get(extension))) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static String mediaIdForEndpoint(Object endpoint) throws Exception {
        Method getMediaId = endpointMediaIdMethod;
        if (getMediaId == null) {
            Class<?> mediaIdHelper = Class.forName(
                    mediaIdHelperClassName, false,
                    RestoreAndroidAutoPlaylistsPatch.class.getClassLoader());
            getMediaId = mediaIdHelper.getDeclaredMethod(
                    mediaIdHelperMethodName, endpoint.getClass());
            getMediaId.setAccessible(true);
            endpointMediaIdMethod = getMediaId;
        }
        Object value = getMediaId.invoke(null, endpoint);
        return value instanceof String ? (String) value : null;
    }

    private static boolean isResponsiveRenderer(Object value) {
        return value != null && value.getClass().getName().equals(responsiveRendererClassName);
    }

    private static String responsiveRendererTitle(Object renderer) throws Exception {
        return renderText(readField(renderer, RESPONSIVE_RENDERER_TITLE_FIELD_NAME));
    }

    private static String responsiveRendererSubtitle(Object renderer) throws Exception {
        return renderText(readField(renderer, RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME));
    }

    private static Uri responsiveRendererArtwork(Object renderer) throws Exception {
        return findArtworkUri(readField(renderer, RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME));
    }

    private static String findBrowseId(Object endpoint) {
        Object browseEndpoint = findExtension(endpoint, browseEndpointClassName);
        if (browseEndpoint == null) return null;
        try {
            Object browseId = readField(browseEndpoint, browseIdFieldName);
            return browseId instanceof String && !((String) browseId).isEmpty()
                    ? (String) browseId
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object createPlayableItem(
            String mediaId, String title, String subtitle, Uri iconUri) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitle, null, null, iconUri, null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static Uri findArtworkUri(Object value) {
        if (value == null) return null;
        String[] artworkUrl = new String[1];
        ObjectGraphState state = new ObjectGraphState();
        try {
            walkObjectGraph(value, state, candidate -> {
                if (candidate instanceof CharSequence &&
                        candidate.toString().startsWith("https://")) {
                    artworkUrl[0] = candidate.toString();
                    state.stopped = true;
                }
                return false;
            });
        } catch (Throwable ignored) {
        }
        return artworkUrl[0] == null ? null : Uri.parse(artworkUrl[0]);
    }

    private static String renderText(Object text) {
        if (text == null) return "";
        try {
            Object direct = readField(text, TEXT_DIRECT_VALUE_FIELD_NAME);
            if (direct instanceof String && !((String) direct).isEmpty()) return (String) direct;
            Object runs = readField(text, TEXT_RUNS_FIELD_NAME);
            if (!(runs instanceof Iterable<?>)) return "";
            StringBuilder combinedText = new StringBuilder();
            for (Object run : (Iterable<?>) runs) {
                Object value = readField(run, TEXT_RUN_VALUE_FIELD_NAME);
                if (value instanceof String) combinedText.append((String) value);
            }
            return combinedText.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Object readField(Object instance, String name) throws Exception {
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
        return null;
    }

    private static Object readFieldPath(Object instance, String[] fieldNames) throws Exception {
        Object value = instance;
        if (fieldNames == null) return null;
        for (String fieldName : fieldNames) value = readField(value, fieldName);
        return value;
    }

    private static Iterator<?> extensionEntries(Object message) {
        try {
            Object extensionMap = readField(message, EXTENSION_MAP_FIELD_NAME);
            if (extensionMap == null) return null;
            Method iteratorMethod = extensionMap.getClass().getDeclaredMethod(
                    EXTENSION_ITERATOR_METHOD_NAME);
            iteratorMethod.setAccessible(true);
            Object iterator = iteratorMethod.invoke(extensionMap);
            return iterator instanceof Iterator<?> ? (Iterator<?>) iterator : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findExtension(Object message, String className) {
        Iterator<?> entries = extensionEntries(message);
        if (entries == null) return null;
        while (entries.hasNext()) {
            Object entry = entries.next();
            if (!(entry instanceof Map.Entry<?, ?>)) continue;
            Object extension = ((Map.Entry<?, ?>) entry).getValue();
            if (extension != null && extension.getClass().getName().equals(className)) {
                return extension;
            }
        }
        return null;
    }

    private static void walkObjectGraph(
            Object value, ObjectGraphState state, ObjectVisitor visitor) throws Exception {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        enqueue(value, state, pending);

        while (!pending.isEmpty() && !state.stopped) {
            Object current = pending.removeFirst();
            if (visitor.skipChildren(current)) continue;

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
                    Object entry = entries.next();
                    if (entry instanceof Map.Entry<?, ?>) {
                        enqueue(((Map.Entry<?, ?>) entry).getValue(), state, pending);
                    }
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
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static boolean isObfuscatedYtmClass(Class<?> valueClass) {
        return valueClass.getName().indexOf('.') < 0;
    }

    private static void enqueue(
            Object value, ObjectGraphState state, ArrayDeque<Object> pending) {
        if (value != null && state.seen.put(value, Boolean.TRUE) == null) pending.addLast(value);
    }

    private static long carClientTypeForContainer(byte[] mediaId, long expectedContainerType) {
        try {
            int[] offset = {0};
            long containerType = CONTAINER_TYPE_DEFAULT;
            long clientType = 0;
            while (offset[0] < mediaId.length) {
                long tag = readVarint(mediaId, offset);
                int fieldNumber = (int) (tag >>> PROTOBUF_TAG_FIELD_SHIFT);
                int wireType = (int) (tag & PROTOBUF_TAG_WIRE_TYPE_MASK);
                if (wireType == PROTOBUF_WIRE_VARINT) {
                    long fieldValue = readVarint(mediaId, offset);
                    if (fieldNumber == MEDIA_ITEM_CONTAINER_TYPE_FIELD_NUMBER) {
                        containerType = fieldValue;
                    }
                    if (fieldNumber == MEDIA_ITEM_CLIENT_TYPE_FIELD_NUMBER) {
                        clientType = fieldValue;
                    }
                } else {
                    skipField(mediaId, offset, wireType);
                }
            }
            if (containerType != expectedContainerType) return UNSUPPORTED_CAR_CLIENT_TYPE;
            return clientType == CLIENT_TYPE_ANDROID_AUTO ||
                    clientType == CLIENT_TYPE_ANDROID_AUTOMOTIVE
                    ? clientType
                    : UNSUPPORTED_CAR_CLIENT_TYPE;
        } catch (Throwable ignored) {
            return UNSUPPORTED_CAR_CLIENT_TYPE;
        }
    }

    private static boolean isNativePlaylistNode(Object loadResult) {
        String mediaId = mediaId(loadResult);
        if (mediaId == null) return false;
        long clientType = carClientTypeForContainer(
                decodeMediaId(mediaId), CONTAINER_TYPE_DEFAULT);
        return mediaId.equals(NATIVE_PLAYLIST_NODE_MEDIA_IDS.get(clientType));
    }

    private static byte[] decodeMediaId(String mediaId) {
        try {
            return Base64.decode(mediaId, MEDIA_ID_BASE64_OPTIONS);
        } catch (RuntimeException ignored) {
            return new byte[0];
        }
    }

    private static void skipField(byte[] message, int[] offset, int wireType) {
        if (wireType == PROTOBUF_WIRE_VARINT) {
            readVarint(message, offset);
            return;
        }

        int length;
        if (wireType == PROTOBUF_WIRE_FIXED_64) {
            length = Long.BYTES;
        } else if (wireType == PROTOBUF_WIRE_LENGTH_DELIMITED) {
            long value = readVarint(message, offset);
            if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("Field too large");
            length = (int) value;
        } else if (wireType == PROTOBUF_WIRE_FIXED_32) {
            length = Integer.BYTES;
        } else {
            throw new IllegalArgumentException("Unsupported wire type");
        }
        if (length > message.length - offset[0]) {
            throw new IllegalArgumentException("Truncated field");
        }
        offset[0] += length;
    }

    private static void deliver(Object loadResult, List<Object> items) {
        try {
            invokeDelivery(loadResult, items);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", error);
        }
    }

    static void invokeDelivery(Object loadResult, List<Object> items) throws Exception {
        // YouTube Music 9.30 added an optional second argument; null keeps the earlier behavior.
        Object[] arguments = new Object[resultDeliveryParameterCount];
        arguments[0] = items;
        invoke(loadResult, resultDeliveryMethodName, arguments);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        for (Class<?> owner = target.getClass(); owner != null && owner != Object.class;
                owner = owner.getSuperclass()) {
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(name) ||
                        !parametersAccept(method.getParameterTypes(), arguments)) continue;
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
            Object value = readFieldPath(loadResult, loadResultMediaIdFieldPath);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long readVarint(byte[] data, int[] offset) {
        long value = 0;
        for (int shift = 0; shift < Long.SIZE; shift += VARINT_PAYLOAD_BITS) {
            if (offset[0] >= data.length) throw new IllegalArgumentException("Truncated varint");
            int current = data[offset[0]++] & UNSIGNED_BYTE_MASK;
            value |= (long) (current & VARINT_PAYLOAD_MASK) << shift;
            if ((current & VARINT_CONTINUATION_BIT) == 0) return value;
        }
        throw new IllegalArgumentException("Invalid varint");
    }

    private interface ObjectVisitor {
        boolean skipChildren(Object value) throws Exception;
    }

    private static final class ObjectGraphState {
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private boolean stopped;
    }

    private static final class LibraryState {
        private final List<Object> items = new ArrayList<>();
        private final Set<String> browseIds = new HashSet<>();
        private final ObjectGraphState objectGraphState = new ObjectGraphState();
    }

}
