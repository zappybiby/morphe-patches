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

import java.io.ByteArrayOutputStream;
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
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String YTM_COLLECTION_BROWSE_ID_PREFIX = "VL";
    private static final String COLLECTION_MEDIA_ID_PREFIX = "morphe:aa-collection:";

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
    private static final int SCHEMA_MEDIA_ITEM_DESCRIPTION_FIELD_INDEX = 15;
    private static final int SCHEMA_DESCRIPTION_MEDIA_ID_FIELD_INDEX = 16;
    private static final int SCHEMA_DESCRIPTION_TITLE_FIELD_INDEX = 17;
    private static final int SCHEMA_PLAYLIST_TITLE_RESOURCE_ID_INDEX = 18;
    private static final int SCHEMA_SIZE = SCHEMA_PLAYLIST_TITLE_RESOURCE_ID_INDEX + 1;

    // MediaItemInfo protobuf
    private static final int MEDIA_ITEM_ENDPOINT_FIELD_NUMBER = 3;
    private static final int MEDIA_ITEM_CONTAINER_TYPE_FIELD_NUMBER = 4;
    private static final int MEDIA_ITEM_CLIENT_TYPE_FIELD_NUMBER = 5;
    private static final long CONTAINER_TYPE_DEFAULT = 0;
    private static final long CONTAINER_TYPE_LIBRARY = 3;
    private static final long CLIENT_TYPE_ANDROID_AUTO = 10;
    private static final long CLIENT_TYPE_ANDROID_AUTOMOTIVE = 13;
    private static final long UNSUPPORTED_CAR_CLIENT_TYPE = 0;
    // Player config data, including the init-playback URL, supported decoders, and device flags,
    // added about 1,840 characters (roughly 3.7 KB in a Parcel) to every media ID. A 285-track
    // playlist then hit Android Auto's 419,840-byte result limit and stopped at 102 tracks, so
    // leave that optional data out.
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
    private static final Executor REQUEST_EXECUTOR = Utils::runOnBackgroundThread;
    private static final ConcurrentHashMap<String, CompletableFuture<List<Object>>> IN_FLIGHT_LOADS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, String> NATIVE_PLAYLIST_NODE_MEDIA_IDS =
            new ConcurrentHashMap<>();

    private static volatile Object authenticatedBrowseService;
    private static volatile String[] loadResultMediaIdFieldPath;
    private static volatile String browseIdSetterMethodName;
    private static volatile String continuationSetterMethodName;
    private static volatile String browseBuilderFactoryMethodName;
    private static volatile String browseRequestMethodName;
    private static volatile String clientDataSetterMethodName;
    private static volatile String resultDeliveryMethodName;
    private static volatile int resultDeliveryParameterCount;
    private static volatile String mediaItemDescriptionFieldName;
    private static volatile String descriptionMediaIdFieldName;
    private static volatile String descriptionTitleFieldName;
    private static volatile int playlistTitleResourceId;

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

            PlaylistPageMapper.configure(
                    schemaValues[SCHEMA_RESPONSIVE_RENDERER_CLASS_INDEX],
                    schemaValues[SCHEMA_MUSIC_SHELF_RENDERER_CLASS_INDEX],
                    schemaValues[SCHEMA_RESPONSIVE_RENDERER_ENDPOINT_FIELD_INDEX],
                    schemaValues[SCHEMA_MEDIA_ID_HELPER_CLASS_INDEX],
                    schemaValues[SCHEMA_MEDIA_ID_HELPER_METHOD_INDEX],
                    schemaValues[SCHEMA_BROWSE_ENDPOINT_CLASS_INDEX],
                    schemaValues[SCHEMA_BROWSE_ID_FIELD_INDEX]);
            loadResultMediaIdFieldPath = schemaValues[SCHEMA_MEDIA_ID_FIELD_PATH_INDEX].split(",");
            browseIdSetterMethodName = schemaValues[SCHEMA_BROWSE_ID_SETTER_INDEX];
            continuationSetterMethodName = schemaValues[SCHEMA_CONTINUATION_SETTER_INDEX];
            browseBuilderFactoryMethodName = schemaValues[SCHEMA_BROWSE_BUILDER_FACTORY_INDEX];
            browseRequestMethodName = schemaValues[SCHEMA_BROWSE_REQUEST_METHOD_INDEX];
            clientDataSetterMethodName = schemaValues[SCHEMA_CLIENT_DATA_SETTER_INDEX];
            resultDeliveryMethodName = schemaValues[SCHEMA_RESULT_DELIVERY_METHOD_INDEX];
            resultDeliveryParameterCount = deliveryParameterCount;
            mediaItemDescriptionFieldName = schemaValues[SCHEMA_MEDIA_ITEM_DESCRIPTION_FIELD_INDEX];
            descriptionMediaIdFieldName = schemaValues[SCHEMA_DESCRIPTION_MEDIA_ID_FIELD_INDEX];
            descriptionTitleFieldName = schemaValues[SCHEMA_DESCRIPTION_TITLE_FIELD_INDEX];
            playlistTitleResourceId = titleResourceId;
        } catch (Throwable error) {
            Logger.printException(
                    () -> "Could not configure Android Auto playlist schema", error);
        }
    }

    public static void initialize(Object service) {
        if (service == null) return;
        if (authenticatedBrowseService != service) NATIVE_PLAYLIST_NODE_MEDIA_IDS.clear();
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
                Object description = PlaylistPageMapper.readField(
                        item, mediaItemDescriptionFieldName);
                if (description == null) continue;

                Object title = PlaylistPageMapper.readField(description, descriptionTitleFieldName);
                if (!(title instanceof CharSequence) ||
                        !playlistTitle.contentEquals((CharSequence) title)) continue;

                Object mediaId = PlaylistPageMapper.readField(
                        description, descriptionMediaIdFieldName);
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
            String browseId = PlaylistPageMapper.findBrowseId(
                    PlaylistPageMapper.responsiveRendererEndpoint(renderer));
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
            if (containerType != expectedContainerType) return 0;
            return clientType == CLIENT_TYPE_ANDROID_AUTO
                    || clientType == CLIENT_TYPE_ANDROID_AUTOMOTIVE
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
        int[] offset = {0};
        while (offset[0] < message.length) {
            long tag = readVarint(message, offset);
            int fieldNumber = (int) (tag >>> PROTOBUF_TAG_FIELD_SHIFT);
            int wireType = (int) (tag & PROTOBUF_TAG_WIRE_TYPE_MASK);
            if (fieldNumber == 0) throw new IllegalArgumentException("Invalid field number");

            int payloadStart = skipField(message, offset, wireType);
            int fieldEnd = offset[0];
            if (fieldNumber != fieldPath[fieldPathIndex]) continue;
            if (fieldPathIndex == fieldPath.length - 1) {
                return wireType == PROTOBUF_WIRE_LENGTH_DELIMITED && fieldEnd > payloadStart;
            }
            if (wireType != PROTOBUF_WIRE_LENGTH_DELIMITED) continue;

            byte[] nestedMessage = new byte[fieldEnd - payloadStart];
            System.arraycopy(message, payloadStart, nestedMessage, 0, nestedMessage.length);
            if (hasNonEmptyFieldPath(nestedMessage, fieldPath, fieldPathIndex + 1)) return true;
        }
        return false;
    }

    private static byte[] removeOptionalPlayerConfig(byte[] message, int fieldPathIndex) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(message.length);
        int[] offset = {0};
        boolean removed = false;

        while (offset[0] < message.length) {
            int fieldStart = offset[0];
            long tag = readVarint(message, offset);
            int fieldNumber = (int) (tag >>> PROTOBUF_TAG_FIELD_SHIFT);
            int wireType = (int) (tag & PROTOBUF_TAG_WIRE_TYPE_MASK);
            if (fieldNumber == 0) throw new IllegalArgumentException("Invalid field number");

            int tagEnd = offset[0];
            int payloadStart = skipField(message, offset, wireType);
            int fieldEnd = offset[0];
            if (fieldNumber != OPTIONAL_PLAYER_CONFIG_FIELD_PATH[fieldPathIndex]) {
                output.write(message, fieldStart, fieldEnd - fieldStart);
                continue;
            }

            if (fieldPathIndex == OPTIONAL_PLAYER_CONFIG_FIELD_PATH.length - 1) {
                if (wireType != PROTOBUF_WIRE_LENGTH_DELIMITED) {
                    throw new IllegalArgumentException("Invalid player config");
                }
                if (removed) throw new IllegalArgumentException("Repeated player config");
                removed = true;
                continue;
            }
            if (wireType != PROTOBUF_WIRE_LENGTH_DELIMITED) {
                throw new IllegalArgumentException("Invalid nested message");
            }

            byte[] nestedMessage = new byte[fieldEnd - payloadStart];
            System.arraycopy(message, payloadStart, nestedMessage, 0, nestedMessage.length);
            byte[] nested = removeOptionalPlayerConfig(nestedMessage, fieldPathIndex + 1);
            if (nested == null) {
                output.write(message, fieldStart, fieldEnd - fieldStart);
                continue;
            }
            if (removed) throw new IllegalArgumentException("Repeated player config");

            output.write(message, fieldStart, tagEnd - fieldStart);
            writeVarint(output, nested.length);
            output.write(nested, 0, nested.length);
            removed = true;
        }
        return removed ? output.toByteArray() : null;
    }

    private static int skipField(byte[] message, int[] offset, int wireType) {
        if (wireType == PROTOBUF_WIRE_VARINT) {
            readVarint(message, offset);
            return offset[0];
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
        int payloadStart = offset[0];
        offset[0] += length;
        return payloadStart;
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
        // YouTube Music 9.30 added an optional second argument; null keeps the earlier behavior.
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

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        while ((value & ~VARINT_PAYLOAD_MASK) != 0) {
            output.write((value & VARINT_PAYLOAD_MASK) | VARINT_CONTINUATION_BIT);
            value >>>= VARINT_PAYLOAD_BITS;
        }
        output.write(value);
    }

    private static final class LibraryState {
        private final List<Object> items = new ArrayList<>();
        private final Set<String> browseIds = new HashSet<>();
        private final PlaylistPageMapper.ObjectGraphState objectGraphState =
                new PlaylistPageMapper.ObjectGraphState();
    }

}
