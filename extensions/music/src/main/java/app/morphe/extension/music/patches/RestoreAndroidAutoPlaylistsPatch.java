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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String YTM_COLLECTION_BROWSE_ID_PREFIX = "VL";

    // YTM 9.15/9.29/9.30/9.31 MediaItemInfo: field 4 is container type and field 5 is client type.
    // Library is 3; Android Auto and Android Automotive are 10 and 13.
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
    private static volatile String[] responsiveRendererEndpointFieldNames;
    private static volatile String responsiveRendererArtworkFieldName;
    private static volatile String responsiveRendererTitleFieldName;
    private static volatile String responsiveRendererSubtitleFieldName;
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
    private static volatile TextAccessors textAccessors;
    private static volatile Field textRunValueField;
    private static volatile ExtensionAccessors extensionAccessors;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    public static void configure(String encodedSchema) {
        String[] schemaValues = encodedSchema.split("\\|");
        int index = 0;
        responsiveRendererClassName = schemaValues[index++];
        playlistEndpointClassName = schemaValues[index++];
        responsiveRendererEndpointFieldNames =
                new String[]{schemaValues[index++], schemaValues[index++]};
        mediaIdHelperClassName = schemaValues[index++];
        mediaIdHelperMethodName = schemaValues[index++];
        browseEndpointClassName = schemaValues[index++];
        browseIdFieldName = schemaValues[index++];
        browseIdSetterMethodName = schemaValues[index++];
        loadResultMediaIdFieldPath = schemaValues[index++].split(",");
        browseBuilderFactoryMethodName = schemaValues[index++];
        browseRequestMethodName = schemaValues[index++];
        clientDataSetterMethodName = schemaValues[index++];
        resultDeliveryMethodName = schemaValues[index++];
        resultDeliveryParameterCount = Integer.parseInt(schemaValues[index++]);
        mediaItemDescriptionFieldName = schemaValues[index++];
        descriptionMediaIdFieldName = schemaValues[index++];
        descriptionTitleFieldName = schemaValues[index++];
        playlistTitleResourceId = Integer.parseInt(schemaValues[index++]);
        responsiveRendererArtworkFieldName = schemaValues[index++];
        responsiveRendererTitleFieldName = schemaValues[index++];
        responsiveRendererSubtitleFieldName = schemaValues[index];
    }

    public static void initialize(Object service) {
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
        if (authenticatedBrowseService == null || !isNativePlaylistNode(loadResult)) {
            return false;
        }
        loadLibrary().thenAccept(items -> deliver(loadResult, items));
        return true;
    }

    public static void rememberNativePlaylistNode(Object loadResult, List<?> items) {
        if (authenticatedBrowseService == null || items.isEmpty()) {
            return;
        }
        String libraryMediaId = mediaId(loadResult);
        if (libraryMediaId == null) return;
        long clientType = carClientTypeForContainer(
                decodeMediaId(libraryMediaId), CONTAINER_TYPE_LIBRARY);
        if (clientType == UNSUPPORTED_CAR_CLIENT_TYPE) return;

        String playlistNodeMediaId;
        try {
            playlistNodeMediaId = findNativePlaylistsMediaId(items, clientType);
        } catch (ReflectiveOperationException error) {
            Logger.printException(() -> "Could not read Android Auto Library", error);
            return;
        }
        if (playlistNodeMediaId == null) {
            NATIVE_PLAYLIST_NODE_MEDIA_IDS.remove(clientType);
        } else {
            NATIVE_PLAYLIST_NODE_MEDIA_IDS.put(clientType, playlistNodeMediaId);
            Logger.printDebug(() -> "Matched Playlists among " +
                    items.size() + " Library items");
        }
    }

    // YTM 9.15/9.29/9.30/9.31: Library returns a localized Playlists row with a car-specific
    // media ID. Match its resource title and MediaItemInfo so other Library rows are left alone.
    private static String findNativePlaylistsMediaId(
            List<?> items, long clientType) throws ReflectiveOperationException {
        String playlistTitle = Utils.getContext().getString(playlistTitleResourceId);
        String uniquePlaylistMediaId = null;
        for (Object item : items) {
            Object description = readField(item, mediaItemDescriptionFieldName);
            CharSequence title = (CharSequence) readField(
                    description, descriptionTitleFieldName);
            if (!playlistTitle.contentEquals(title)) continue;

            String candidateMediaId = (String) readField(
                    description, descriptionMediaIdFieldName);
            if (carClientTypeForContainer(
                    decodeMediaId(candidateMediaId), CONTAINER_TYPE_DEFAULT) != clientType) {
                continue;
            }
            if (uniquePlaylistMediaId != null &&
                    !uniquePlaylistMediaId.equals(candidateMediaId)) return null;
            uniquePlaylistMediaId = candidateMediaId;
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
                } catch (ExecutionException | CancellationException error) {
                    responseFuture.completeExceptionally(error);
                }
            }, REQUEST_EXECUTOR);
        } catch (ReflectiveOperationException error) {
            responseFuture.completeExceptionally(error);
        }
        return responseFuture;
    }

    private static List<Object> mapLibrary(Object response) {
        LibraryState state = new LibraryState();
        try {
            // YTM 9.15/9.29/9.30/9.31: Library playlists are nested in responsive-renderer
            // protobuf extensions. Walk the response and map only those renderer messages.
            walkObjectGraph(response, state.objectGraphState, value -> {
                if (!isResponsiveRenderer(value)) return false;
                appendLibraryPlaylist(value, state);
                return true;
            });
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not map Library response", error);
        }
        Logger.printDebug(() -> "Mapped playable Library playlists: " + state.items.size());
        return state.items;
    }

    private static void appendLibraryPlaylist(
            Object renderer, LibraryState state) throws ReflectiveOperationException {
        String browseId = responsiveRendererBrowseId(renderer);
        if (browseId == null || !browseId.startsWith(YTM_COLLECTION_BROWSE_ID_PREFIX) ||
                state.browseIds.contains(browseId)) {
            return;
        }
        String playlistId = browseId.substring(YTM_COLLECTION_BROWSE_ID_PREFIX.length());
        String playlistMediaId = responsiveRendererPlaylistMediaId(renderer, playlistId);
        if (playlistMediaId == null) return;

        String title = responsiveRendererTitle(renderer);
        if (title.isEmpty()) return;
        state.browseIds.add(browseId);
        state.items.add(createPlayableItem(
                playlistMediaId,
                title,
                responsiveRendererSubtitle(renderer),
                responsiveRendererArtwork(renderer)));
    }

    private static String responsiveRendererBrowseId(
            Object renderer) throws ReflectiveOperationException {
        for (String fieldName : responsiveRendererEndpointFieldNames) {
            Object endpoint = readField(renderer, fieldName);
            if (endpoint == null) continue;
            String browseId = findBrowseId(endpoint);
            if (browseId != null) return browseId;
        }
        return null;
    }

    private static String responsiveRendererPlaylistMediaId(
            Object renderer, String playlistId) throws ReflectiveOperationException {
        for (String fieldName : responsiveRendererEndpointFieldNames) {
            Object endpoint = readField(renderer, fieldName);
            if (endpoint == null || !endpointContainsPlaylistId(endpoint, playlistId)) continue;
            String mediaId = mediaIdForEndpoint(endpoint);
            if (mediaId != null && !mediaId.isEmpty()) return mediaId;
        }
        return null;
    }

    // YTM 9.15/9.29/9.30/9.31: the playlist endpoint extension contains the playlist ID.
    // Match its value instead of relying on the generated String field name.
    private static boolean endpointContainsPlaylistId(
            Object endpoint, String playlistId) throws ReflectiveOperationException {
        Object extension = findExtension(endpoint, playlistEndpointClassName);
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
        return (String) value;
    }

    private static boolean isResponsiveRenderer(Object value) {
        return value.getClass().getName().equals(responsiveRendererClassName);
    }

    private static String responsiveRendererTitle(
            Object renderer) throws ReflectiveOperationException {
        return renderText(readField(renderer, responsiveRendererTitleFieldName));
    }

    private static String responsiveRendererSubtitle(
            Object renderer) throws ReflectiveOperationException {
        return renderText(readField(renderer, responsiveRendererSubtitleFieldName));
    }

    private static Uri responsiveRendererArtwork(
            Object renderer) throws ReflectiveOperationException {
        return findArtworkUri(readField(renderer, responsiveRendererArtworkFieldName));
    }

    private static String findBrowseId(
            Object endpoint) throws ReflectiveOperationException {
        Object browseEndpoint = findExtension(endpoint, browseEndpointClassName);
        if (browseEndpoint == null) return null;
        String browseId = (String) readField(browseEndpoint, browseIdFieldName);
        return browseId.isEmpty() ? null : browseId;
    }

    private static Object createPlayableItem(
            String mediaId, String title, String subtitle, Uri iconUri) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitle, null, null, iconUri, null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    // YTM 9.15/9.29/9.30/9.31 testing: the first HTTPS URL in this artwork message is the
    // playlist thumbnail. Stop after finding it instead of traversing the remaining artwork data.
    private static Uri findArtworkUri(
            Object value) throws ReflectiveOperationException {
        if (value == null) return null;
        String[] artworkUrl = new String[1];
        ObjectGraphState state = new ObjectGraphState();
        walkObjectGraph(value, state, candidate -> {
            if (candidate instanceof CharSequence &&
                    candidate.toString().startsWith("https://")) {
                artworkUrl[0] = candidate.toString();
                state.stopped = true;
            }
            return false;
        });
        return artworkUrl[0] == null ? null : Uri.parse(artworkUrl[0]);
    }

    // YTM 9.15/9.29/9.30/9.31: text messages have one direct String and one iterable field of
    // text runs. Each run has one String, so resolve these fields by type rather than generated names.
    private static String renderText(Object text) throws IllegalAccessException {
        if (text == null) return "";

        TextAccessors accessors = textAccessors;
        if (accessors == null) {
            accessors = resolveTextAccessors(text.getClass());
            textAccessors = accessors;
        }
        String direct = (String) accessors.directValue.get(text);
        if (!direct.isEmpty()) return direct;

        StringBuilder combinedText = new StringBuilder();
        for (Object run : (Iterable<?>) accessors.runs.get(text)) {
            Field valueField = textRunValueField;
            if (valueField == null) {
                valueField = resolveTextRunValueField(run.getClass());
                textRunValueField = valueField;
            }
            combinedText.append((String) valueField.get(run));
        }
        return combinedText.toString();
    }

    private static TextAccessors resolveTextAccessors(Class<?> textClass) {
        return new TextAccessors(
                singleInstanceField(textClass, field -> field.getType() == String.class),
                singleInstanceField(textClass,
                        field -> Iterable.class.isAssignableFrom(field.getType())));
    }

    private static Field resolveTextRunValueField(Class<?> runClass) {
        return singleInstanceField(runClass, field -> field.getType() == String.class);
    }

    private static Field singleInstanceField(Class<?> owner, Predicate<Field> predicate) {
        Field result = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !predicate.test(field)) continue;
            if (result != null) {
                throw new IllegalStateException("Multiple matching fields in " + owner.getName());
            }
            result = field;
        }
        if (result == null) {
            throw new IllegalStateException("No matching field in " + owner.getName());
        }
        result.setAccessible(true);
        return result;
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

    private static Object readFieldPath(
            Object instance, String[] fieldNames) throws ReflectiveOperationException {
        Object value = instance;
        for (String fieldName : fieldNames) value = readField(value, fieldName);
        return value;
    }

    // YTM 9.15/9.29/9.30/9.31: extendable protobuf messages inherit one extension map with one
    // iterator. Some response messages are not extendable, so reuse these accessors only when compatible.
    private static Iterator<?> extensionEntries(
            Object message) throws ReflectiveOperationException {
        ExtensionAccessors accessors = extensionAccessors;
        if (accessors == null) {
            accessors = resolveExtensionAccessors(message.getClass());
            if (accessors == null) return null;
            extensionAccessors = accessors;
        }
        if (!accessors.extensionMap.getDeclaringClass().isInstance(message)) return null;
        Object extensionMap = accessors.extensionMap.get(message);
        return (Iterator<?>) accessors.iteratorMethod.invoke(extensionMap);
    }

    private static ExtensionAccessors resolveExtensionAccessors(Class<?> messageClass) {
        ExtensionAccessors accessors = null;
        for (Class<?> owner = messageClass.getSuperclass();
                owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Method iteratorMethod = singleIteratorMethod(field.getType());
                if (iteratorMethod == null) continue;
                if (accessors != null) {
                    throw new IllegalStateException("Multiple extension maps in " +
                            messageClass.getName());
                }
                field.setAccessible(true);
                iteratorMethod.setAccessible(true);
                accessors = new ExtensionAccessors(field, iteratorMethod);
            }
        }
        return accessors;
    }

    private static Method singleIteratorMethod(Class<?> owner) {
        Method result = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) ||
                    method.getParameterTypes().length != 0 ||
                    method.getReturnType() != Iterator.class) continue;
            if (result != null) return null;
            result = method;
        }
        return result;
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

    // YTM 9.15/9.29/9.30/9.31: Library renderers may be nested in fields, lists, or protobuf
    // extensions. Traverse generated YTM objects until the requested renderer is found.
    private static void walkObjectGraph(
            Object value, ObjectGraphState state, ObjectVisitor visitor)
            throws ReflectiveOperationException {
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
        } catch (IllegalArgumentException ignored) {
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
        } catch (IllegalArgumentException ignored) {
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
        } catch (ReflectiveOperationException error) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", error);
        }
    }

    static void invokeDelivery(
            Object loadResult, List<Object> items) throws ReflectiveOperationException {
        // YTM 9.15/9.29: the one-argument wrapper supplied a null interaction context.
        // YTM 9.30/9.31: the wrapper is gone, so supply the same null here.
        Object[] arguments = new Object[resultDeliveryParameterCount];
        arguments[0] = items;
        invoke(loadResult, resultDeliveryMethodName, arguments);
    }

    private static Object invoke(
            Object target, String name, Object... arguments) throws ReflectiveOperationException {
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
            return (String) readFieldPath(loadResult, loadResultMediaIdFieldPath);
        } catch (ReflectiveOperationException ignored) {
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
        boolean skipChildren(Object value) throws ReflectiveOperationException;
    }

    private static final class TextAccessors {
        private final Field directValue;
        private final Field runs;

        private TextAccessors(Field directValue, Field runs) {
            this.directValue = directValue;
            this.runs = runs;
        }
    }

    private static final class ExtensionAccessors {
        private final Field extensionMap;
        private final Method iteratorMethod;

        private ExtensionAccessors(Field extensionMap, Method iteratorMethod) {
            this.extensionMap = extensionMap;
            this.iteratorMethod = iteratorMethod;
        }
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
