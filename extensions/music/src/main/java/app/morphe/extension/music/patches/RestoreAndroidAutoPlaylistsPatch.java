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
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final long BROWSE_REQUEST_TIMEOUT_MILLISECONDS = 30_000;
    private static final int PLAYLIST_ARTWORK_SIZE_PX = 544;
    // YTM 9.15/9.29/9.30/9.31: thumbnail extension 164480666 stores its list at c.c and
    // each URL in c. Library URLs request 60-192 px; the same Google CDN URL supports 544 px.
    private static final String[] PLAYLIST_THUMBNAIL_LIST_FIELD_PATH = {"c", "c"};
    private static final String PLAYLIST_THUMBNAIL_URL_FIELD_NAME = "c";
    // YTM 9.15/9.29/9.30/9.31: text messages store either a direct value in d or runs
    // in c; each run's text is c.
    private static final String TEXT_DIRECT_VALUE_FIELD_NAME = "d";
    private static final String TEXT_RUNS_FIELD_NAME = "c";
    private static final String TEXT_RUN_VALUE_FIELD_NAME = "c";

    private static final Executor REQUEST_EXECUTOR = Utils::runOnBackgroundThread;
    private static final Set<String> NATIVE_PLAYLISTS_NODE_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> DELIVERING_SEARCH_RESULT = new ThreadLocal<>();

    private static volatile Object authenticatedBrowseService;
    private static CompletableFuture<List<PlayablePlaylist>> inFlightLibraryLoad;
    private static volatile RuntimeConfiguration runtimeConfiguration;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /** Stores YTM's Browse service and clears session state when its instance changes. */
    public static void initialize(Object service) {
        if (service == null) return;
        try {
            synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
                if (runtimeConfiguration == null) {
                    runtimeConfiguration = new RuntimeConfiguration();
                }
                if (authenticatedBrowseService != service) {
                    NATIVE_PLAYLISTS_NODE_MEDIA_IDS.clear();
                    inFlightLibraryLoad = null;
                }
                authenticatedBrowseService = service;
            }
            Logger.printDebug(() -> "Authenticated Browse service ready: " +
                    service.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException error) {
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
            loadPlayableLibraryPlaylists().thenAccept(playlists ->
                    deliver(loadResult, mediaItems(playlists)));
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.printException(() -> "Could not start Android Auto playlist request", error);
            return false;
        }
    }

    /** Records media IDs from Android Auto items whose title matches YTM's Playlists resource. */
    public static void rememberNativePlaylistsMediaId(
            String mediaId, CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE)
                .contentEquals(title)) return;
        if (mediaId != null) NATIVE_PLAYLISTS_NODE_MEDIA_IDS.add(mediaId);
    }

    /** Replaces YTM's Android Auto search response with matching Library playlists. */
    public static boolean handleSearchResult(
            Object searchResult, List<MediaBrowserCompat.MediaItem> ignoredNativeItems) {
        if (Boolean.TRUE.equals(DELIVERING_SEARCH_RESULT.get())) return false;

        try {
            if (!isReady()) return false;
            String query = (String) runtimeConfiguration.searchResultQueryField.get(searchResult);
            String normalizedQuery = normalizeSearchQuery(query);
            if (normalizedQuery.isEmpty()) return false;
            // YTM 9.15.51 and 9.31.51 returned unrelated podcasts for non-Premium Android Auto
            // searches, so only return matching Library playlists.
            // TODO: Use YTM's phone search if it can be reused without copying its UI pipeline.
            loadPlayableLibraryPlaylists().whenComplete((libraryItems, error) -> {
                List<MediaBrowserCompat.MediaItem> items = Collections.emptyList();
                if (error == null) {
                    items = matchingPlaylistItems(normalizedQuery, libraryItems);
                }
                deliverSearchResult(searchResult, items);
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.printException(() -> "Could not start Android Auto playlist search", error);
            return false;
        }
    }

    private static String normalizeSearchQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private static List<MediaBrowserCompat.MediaItem> matchingPlaylistItems(
            String normalizedQuery, List<PlayablePlaylist> libraryItems) {
        List<MediaBrowserCompat.MediaItem> matches = new ArrayList<>();
        for (PlayablePlaylist playlist : libraryItems) {
            if (playlist.normalizedTitle.contains(normalizedQuery)) {
                matches.add(playlist.item);
            }
        }
        return matches;
    }

    private static List<MediaBrowserCompat.MediaItem> mediaItems(
            List<PlayablePlaylist> playlists) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>(playlists.size());
        for (PlayablePlaylist playlist : playlists) items.add(playlist.item);
        return items;
    }

    private static void deliverSearchResult(
            Object searchResult, List<MediaBrowserCompat.MediaItem> items) {
        DELIVERING_SEARCH_RESULT.set(true);
        try {
            runtimeConfiguration.searchResultDeliveryMethod.invoke(searchResult, items);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.printException(() -> "Could not deliver Android Auto search results", error);
        } finally {
            DELIVERING_SEARCH_RESULT.remove();
        }
    }

    private static CompletableFuture<List<PlayablePlaylist>>
            loadPlayableLibraryPlaylists() throws ReflectiveOperationException {
        synchronized (RestoreAndroidAutoPlaylistsPatch.class) {
            if (inFlightLibraryLoad != null) return inFlightLibraryLoad;

            CompletableFuture<Object> response = requestBrowse(LIBRARY_BROWSE_ID);
            CompletableFuture<List<PlayablePlaylist>> load = new CompletableFuture<>();
            inFlightLibraryLoad = load;
            // Only concurrent requests share work. A later open reloads the current Library.
            response
                    .thenApply(RestoreAndroidAutoPlaylistsPatch::extractLibraryPlaylists)
                    .thenCompose(RestoreAndroidAutoPlaylistsPatch::createPlayableLibraryItems)
                    .whenComplete((items, error) -> completeLibraryLoad(load, items, error));
            return load;
        }
    }

    private static void completeLibraryLoad(
            CompletableFuture<List<PlayablePlaylist>> load,
            List<PlayablePlaylist> items,
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

    private static void deliver(
            Object loadResult, List<MediaBrowserCompat.MediaItem> items) {
        try {
            invokeDelivery(loadResult, items);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", error);
        }
    }

    private static void invokeDelivery(
            Object loadResult, List<MediaBrowserCompat.MediaItem> items)
            throws ReflectiveOperationException {
        // YTM 9.15/9.29: the one-argument wrapper supplied a null interaction context.
        // YTM 9.30/9.31: the wrapper is gone, so supply the same null here.
        Method deliveryMethod = runtimeConfiguration.resultDeliveryMethod;
        Object[] arguments = new Object[deliveryMethod.getParameterCount()];
        arguments[0] = items;
        deliveryMethod.invoke(loadResult, arguments);
    }

    private static CompletableFuture<Object> requestBrowse(
            String browseId) throws ReflectiveOperationException {
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        RuntimeConfiguration configuration = runtimeConfiguration;
        Object service = authenticatedBrowseService;
        Object builder = configuration.browseBuilderFactoryMethod.invoke(service);
        configuration.browseIdSetterMethod.invoke(builder, browseId);
        // The builder requires a non-null byte array; these Browse pages have no client-data token.
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
        // A claimed request must still complete Android Auto's callback if YTM's future stalls.
        Utils.runOnMainThreadDelayed(() -> {
            TimeoutException error = new TimeoutException("Browse request timed out");
            if (responseFuture.completeExceptionally(error)) browseRequest.cancel(true);
        }, BROWSE_REQUEST_TIMEOUT_MILLISECONDS);
        return responseFuture;
    }

    private static List<LibraryPlaylist> extractLibraryPlaylists(
            Object response) {
        LibraryState state = new LibraryState();
        try {
            walkResponse(response, value -> {
                if (isResponsiveRenderer(value)) {
                    try {
                        appendLibraryPlaylist(value, state);
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        Logger.printException(() -> "Library playlist skipped", error);
                    }
                }
                return false;
            });
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not map Library response", error);
        }
        Logger.printDebug(() -> "Mapped Library playlists: " + state.playlists.size());
        return state.playlists;
    }

    private static void appendLibraryPlaylist(
            Object renderer, LibraryState state) throws ReflectiveOperationException {
        String browseId = responsiveRendererBrowseId(renderer);
        if (browseId == null || state.seenBrowseIds.contains(browseId)) return;
        // YTM 9.15/9.29/9.30/9.31: VLSE opens Episodes for Later but has no playlist play command.
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(browseId)) return;

        String title = responsiveRendererTitle(renderer);
        if (title.isEmpty()) return;
        state.seenBrowseIds.add(browseId);
        state.playlists.add(new LibraryPlaylist(
                browseId,
                browseId.substring(YTM_COLLECTION_BROWSE_ID_PREFIX.length()),
                title,
                optionalResponsiveRendererSubtitle(renderer),
                optionalResponsiveRendererArtwork(renderer)));
    }

    private static String responsiveRendererBrowseId(
            Object renderer) throws ReflectiveOperationException {
        String playlistBrowseId = null;
        for (String fieldName : runtimeConfiguration.responsiveRendererEndpointFieldNames) {
            Object endpoint = readFieldValue(renderer, fieldName);
            if (endpoint == null) continue;
            String browseId = findBrowseId(endpoint);
            if (browseId == null || !browseId.startsWith(YTM_COLLECTION_BROWSE_ID_PREFIX)) continue;
            if (playlistBrowseId != null && !playlistBrowseId.equals(browseId)) return null;
            playlistBrowseId = browseId;
        }
        return playlistBrowseId;
    }

    private static CompletableFuture<List<PlayablePlaylist>>
            createPlayableLibraryItems(List<LibraryPlaylist> playlists) {
        if (playlists.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        CompletableFuture<PlaybackTemplate> templateFuture = new CompletableFuture<>();
        findPlaybackTemplate(playlists, 0, templateFuture);
        return templateFuture.thenApply(template -> {
            List<PlayablePlaylist> items = new ArrayList<>(playlists.size());
            for (LibraryPlaylist playlist : playlists) {
                String mediaId = mediaIdForPlaylist(template, playlist.playlistId);
                items.add(new PlayablePlaylist(
                        normalizeSearchQuery(playlist.title),
                        createPlayableItem(
                                mediaId,
                                playlist.title,
                                playlist.subtitle,
                                playlist.artwork)));
            }
            return items;
        });
    }

    private static void findPlaybackTemplate(
            List<LibraryPlaylist> playlists,
            int index,
            CompletableFuture<PlaybackTemplate> result) {
        if (index >= playlists.size()) {
            result.completeExceptionally(
                    new IllegalStateException("No Library playlist supplied a play command"));
            return;
        }

        LibraryPlaylist playlist = playlists.get(index);
        try {
            // Library rows expose a VL Browse ID but not the play command. A playlist page supplies
            // a command that can be used as the template for every row.
            requestBrowse(playlist.browseId).whenComplete((response, error) -> {
                if (error == null) {
                    try {
                        String mediaId = findPlaylistPlaybackMediaId(
                                response, playlist.playlistId);
                        if (mediaId != null) {
                            result.complete(new PlaybackTemplate(
                                    mediaId, playlist.playlistId));
                            return;
                        }
                    } catch (ReflectiveOperationException | RuntimeException mappingError) {
                        Logger.printException(
                                () -> "Could not read playlist play command", mappingError);
                    }
                }
                findPlaybackTemplate(playlists, index + 1, result);
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            findPlaybackTemplate(playlists, index + 1, result);
        }
    }

    private static String findPlaylistPlaybackMediaId(
            Object response, String playlistId) throws ReflectiveOperationException {
        Class<?> endpointClass = runtimeConfiguration.endpointMediaIdMethod.getParameterTypes()[0];
        String[] mediaId = {null};
        walkResponse(response, value -> {
            if (!endpointClass.isInstance(value) ||
                    !endpointContainsPlaylistId(value, playlistId)) {
                return false;
            }
            String candidate = mediaIdForEndpoint(value);
            if (candidate == null || candidate.isEmpty()) return false;
            mediaId[0] = candidate;
            return true;
        });
        return mediaId[0];
    }

    private static String mediaIdForPlaylist(
            PlaybackTemplate template, String playlistId) {
        if (template.playlistId.equals(playlistId)) return template.mediaId;

        byte[] source = template.playlistId.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = playlistId.getBytes(StandardCharsets.UTF_8);
        byte[] encodedMediaId = Base64.decode(
                template.mediaId,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        ProtobufRewrite rewritten = rewriteProtobuf(encodedMediaId, source, replacement);
        if (!rewritten.valid || rewritten.replacements != 1) {
            throw new IllegalStateException(
                    "Playlist play command did not contain one playlist ID");
        }
        return Base64.encodeToString(
                rewritten.message,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    // The native play command stores its playlist ID inside nested protobuf messages. Re-encode
    // each enclosing length when the replacement ID has a different number of bytes.
    private static ProtobufRewrite rewriteProtobuf(
            byte[] message, byte[] source, byte[] replacement) {
        CodedInputStream input = CodedInputStream.newInstance(message);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(message.length);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        int replacements = 0;
        try {
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 0) return ProtobufRewrite.invalid();
                output.writeUInt32NoTag(tag);

                switch (WireFormat.getTagWireType(tag)) {
                    case WireFormat.WIRETYPE_VARINT:
                        output.writeUInt64NoTag(input.readRawVarint64());
                        break;
                    case WireFormat.WIRETYPE_FIXED64:
                        output.writeFixed64NoTag(input.readRawLittleEndian64());
                        break;
                    case WireFormat.WIRETYPE_LENGTH_DELIMITED:
                        byte[] payload = input.readRawBytes(input.readRawVarint32());
                        byte[] rewrittenPayload = payload;
                        int payloadReplacements = 0;
                        if (Arrays.equals(payload, source)) {
                            rewrittenPayload = replacement;
                            payloadReplacements = 1;
                        } else {
                            ProtobufRewrite nested = rewriteProtobuf(
                                    payload, source, replacement);
                            if (nested.valid && nested.replacements > 0) {
                                rewrittenPayload = nested.message;
                                payloadReplacements = nested.replacements;
                            }
                        }
                        output.writeUInt32NoTag(rewrittenPayload.length);
                        output.writeRawBytes(rewrittenPayload);
                        replacements += payloadReplacements;
                        break;
                    case WireFormat.WIRETYPE_FIXED32:
                        output.writeFixed32NoTag(input.readRawLittleEndian32());
                        break;
                    default:
                        return ProtobufRewrite.invalid();
                }
            }
            output.flush();
            return new ProtobufRewrite(bytes.toByteArray(), replacements, true);
        } catch (IOException error) {
            return ProtobufRewrite.invalid();
        }
    }

    // YTM 9.15/9.29/9.30/9.31: the playlist endpoint extension contains the playlist ID.
    // Match its value instead of relying on the generated String field name.
    private static boolean endpointContainsPlaylistId(
            Object endpoint, String playlistId) throws ReflectiveOperationException {
        Object extension = findExtension(
                endpoint, runtimeConfiguration.playlistEndpointClassName);
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

    // YTM 9.15/9.29/9.30/9.31: the endpoint-to-media-ID method changes names, so the
    // bytecode patch resolves it from the target APK.
    private static String mediaIdForEndpoint(
            Object endpoint) throws ReflectiveOperationException {
        Object value = runtimeConfiguration.endpointMediaIdMethod.invoke(null, endpoint);
        return (String) value;
    }

    private static boolean isResponsiveRenderer(Object value) {
        return value.getClass().getName().equals(
                runtimeConfiguration.responsiveRendererClassName);
    }

    private static String responsiveRendererTitle(
            Object renderer) throws ReflectiveOperationException {
        return renderText(readFieldValue(
                renderer, runtimeConfiguration.responsiveRendererTitleFieldName));
    }

    private static String optionalResponsiveRendererSubtitle(Object renderer) {
        try {
            return renderText(readFieldValue(
                    renderer, runtimeConfiguration.responsiveRendererSubtitleFieldName));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }

    private static Uri optionalResponsiveRendererArtwork(Object renderer) {
        try {
            RuntimeConfiguration configuration = runtimeConfiguration;
            Object artwork = readFieldValue(
                    renderer, configuration.responsiveRendererArtworkFieldName);
            Object thumbnailRenderer = findExtension(
                    artwork, configuration.playlistThumbnailRendererClassName);
            Iterable<?> thumbnails = (Iterable<?>) readFieldPath(
                    thumbnailRenderer, PLAYLIST_THUMBNAIL_LIST_FIELD_PATH);
            for (Object thumbnail : thumbnails) {
                String candidate = (String) readFieldValue(
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
        RuntimeConfiguration configuration = runtimeConfiguration;
        Object browseEndpoint = findExtension(endpoint, configuration.browseEndpointClassName);
        if (browseEndpoint == null) return null;
        String browseId = (String) readFieldValue(
                browseEndpoint, configuration.browseIdFieldName);
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

        String direct = (String) readFieldValue(text, TEXT_DIRECT_VALUE_FIELD_NAME);
        if (!direct.isEmpty()) return direct;

        StringBuilder combinedText = new StringBuilder();
        for (Object run : (Iterable<?>) readFieldValue(text, TEXT_RUNS_FIELD_NAME)) {
            combinedText.append((String) readFieldValue(run, TEXT_RUN_VALUE_FIELD_NAME));
        }
        return combinedText.toString();
    }

    private static Object readFieldValue(
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

    private static Field resolveDeclaredField(
            String ownerClassName, String name) throws ReflectiveOperationException {
        ClassLoader classLoader = RestoreAndroidAutoPlaylistsPatch.class.getClassLoader();
        Field field = Class.forName(ownerClassName, false, classLoader).getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object readFieldPath(
            Object instance, String[] fieldNames) throws ReflectiveOperationException {
        Object value = instance;
        for (String fieldName : fieldNames) value = readFieldValue(value, fieldName);
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

    // YTM 9.15/9.29/9.30/9.31: response messages can appear in generated fields, iterables, or
    // protobuf extensions. Breadth-first traversal covers all three shapes.
    private static void walkResponse(
            Object value, ResponseVisitor visitor) throws ReflectiveOperationException {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> pending = new ArrayDeque<>();
        enqueue(value, visited, pending);

        while (!pending.isEmpty()) {
            Object current = pending.removeFirst();
            if (visitor.visit(current)) return;

            Class<?> valueClass = current.getClass();
            if (current instanceof CharSequence || current instanceof Number ||
                    current instanceof Boolean || valueClass.isEnum()) {
                continue;
            }
            if (current instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) current) enqueue(item, visited, pending);
                continue;
            }

            if (!isObfuscatedYtmClass(valueClass)) continue;
            Iterator<?> entries = extensionEntries(current);
            if (entries != null) {
                while (entries.hasNext()) {
                    enqueue(((Map.Entry<?, ?>) entries.next()).getValue(), visited, pending);
                }
            }
            for (Class<?> owner = valueClass; owner != null && owner != Object.class;
                    owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) ||
                            field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        enqueue(field.get(current), visited, pending);
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
        }
    }

    private static boolean isObfuscatedYtmClass(Class<?> valueClass) {
        // YTM's obfuscated response objects have no package prefix. This keeps traversal out of
        // Android, Java, and support-library object graphs.
        return valueClass.getName().indexOf('.') < 0;
    }

    private static void enqueue(
            Object value, Set<Object> visited, ArrayDeque<Object> pending) {
        if (value != null && visited.add(value)) pending.addLast(value);
    }

    private static boolean isNativePlaylistsNode(Object loadResult) {
        String mediaId = mediaId(loadResult);
        return mediaId != null && NATIVE_PLAYLISTS_NODE_MEDIA_IDS.contains(mediaId);
    }

    private static boolean isReady() {
        return runtimeConfiguration != null && authenticatedBrowseService != null;
    }

    private static String mediaId(Object loadResult) {
        try {
            return (String) readFieldPath(
                    loadResult, runtimeConfiguration.loadResultMediaIdFieldPath);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method resolveMethod(String encodedMethod)
            throws ReflectiveOperationException {
        String[] values = encodedMethod.split("#", -1);
        if (values.length != 3) {
            throw new IllegalArgumentException("Invalid runtime method descriptor");
        }
        ClassLoader classLoader = RestoreAndroidAutoPlaylistsPatch.class.getClassLoader();
        Class<?> owner = Class.forName(values[0], false, classLoader);
        MethodType methodType = MethodType.fromMethodDescriptorString(values[2], classLoader);
        Method method = owner.getDeclaredMethod(values[1], methodType.parameterArray());
        if (method.getReturnType() != methodType.returnType()) {
            throw new NoSuchMethodException("Configured method has a different return type");
        }
        method.setAccessible(true);
        return method;
    }

    // Filled by the patch with class, field, and method details from the target APK.
    // These stay non-final so the compiler does not inline their empty defaults.
    @SuppressWarnings("CanBeFinal")
    private static final class RuntimeValues {
        private static String RESPONSIVE_RENDERER_CLASS_NAME = "";
        private static String PLAYLIST_ENDPOINT_CLASS_NAME = "";
        private static String RESPONSIVE_RENDERER_ENDPOINT_FIELD_NAMES = "";
        private static String ENDPOINT_MEDIA_ID_METHOD = "";
        private static String BROWSE_ENDPOINT_CLASS_NAME = "";
        private static String BROWSE_ID_FIELD_NAME = "";
        private static String BROWSE_ID_SETTER_METHOD = "";
        private static String LOAD_RESULT_MEDIA_ID_FIELD_PATH = "";
        private static String BROWSE_BUILDER_FACTORY_METHOD = "";
        private static String BROWSE_REQUEST_METHOD = "";
        private static String CLIENT_DATA_SETTER_METHOD = "";
        private static String RESULT_DELIVERY_METHOD = "";
        private static String SEARCH_RESULT_CLASS_NAME = "";
        private static String SEARCH_RESULT_QUERY_FIELD_NAME = "";
        private static String SEARCH_RESULT_DELIVERY_METHOD = "";
        private static String RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME = "";
        private static String PLAYLIST_THUMBNAIL_RENDERER_CLASS_NAME = "";
        private static String RESPONSIVE_RENDERER_TITLE_FIELD_NAME = "";
        private static String RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME = "";
        private static String EXTENSION_MAP_CLASS_NAME = "";
        private static String EXTENSION_MAP_FIELD_NAME = "";
        private static String EXTENSION_MAP_ITERATOR_METHOD = "";
    }

    private static final class RuntimeConfiguration {
        private final String responsiveRendererClassName;
        private final String playlistEndpointClassName;
        private final String[] responsiveRendererEndpointFieldNames;
        private final String browseEndpointClassName;
        private final String browseIdFieldName;
        private final String[] loadResultMediaIdFieldPath;
        private final String responsiveRendererArtworkFieldName;
        private final String playlistThumbnailRendererClassName;
        private final String responsiveRendererTitleFieldName;
        private final String responsiveRendererSubtitleFieldName;
        private final Method endpointMediaIdMethod;
        private final Method browseIdSetterMethod;
        private final Method browseBuilderFactoryMethod;
        private final Method browseRequestMethod;
        private final Method clientDataSetterMethod;
        private final Method resultDeliveryMethod;
        private final Field searchResultQueryField;
        private final Method searchResultDeliveryMethod;
        private final Field extensionMapField;
        private final Method extensionMapIteratorMethod;

        private RuntimeConfiguration() throws ReflectiveOperationException {
            responsiveRendererClassName = RuntimeValues.RESPONSIVE_RENDERER_CLASS_NAME;
            playlistEndpointClassName = RuntimeValues.PLAYLIST_ENDPOINT_CLASS_NAME;
            responsiveRendererEndpointFieldNames =
                    RuntimeValues.RESPONSIVE_RENDERER_ENDPOINT_FIELD_NAMES.split(",");
            browseEndpointClassName = RuntimeValues.BROWSE_ENDPOINT_CLASS_NAME;
            browseIdFieldName = RuntimeValues.BROWSE_ID_FIELD_NAME;
            loadResultMediaIdFieldPath = RuntimeValues.LOAD_RESULT_MEDIA_ID_FIELD_PATH.split(",");
            responsiveRendererArtworkFieldName =
                    RuntimeValues.RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME;
            playlistThumbnailRendererClassName =
                    RuntimeValues.PLAYLIST_THUMBNAIL_RENDERER_CLASS_NAME;
            responsiveRendererTitleFieldName =
                    RuntimeValues.RESPONSIVE_RENDERER_TITLE_FIELD_NAME;
            responsiveRendererSubtitleFieldName =
                    RuntimeValues.RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME;
            endpointMediaIdMethod = resolveMethod(RuntimeValues.ENDPOINT_MEDIA_ID_METHOD);
            browseIdSetterMethod = resolveMethod(RuntimeValues.BROWSE_ID_SETTER_METHOD);
            browseBuilderFactoryMethod = resolveMethod(RuntimeValues.BROWSE_BUILDER_FACTORY_METHOD);
            browseRequestMethod = resolveMethod(RuntimeValues.BROWSE_REQUEST_METHOD);
            clientDataSetterMethod = resolveMethod(RuntimeValues.CLIENT_DATA_SETTER_METHOD);
            resultDeliveryMethod = resolveMethod(RuntimeValues.RESULT_DELIVERY_METHOD);
            searchResultQueryField = resolveDeclaredField(
                    RuntimeValues.SEARCH_RESULT_CLASS_NAME,
                    RuntimeValues.SEARCH_RESULT_QUERY_FIELD_NAME);
            searchResultDeliveryMethod = resolveMethod(RuntimeValues.SEARCH_RESULT_DELIVERY_METHOD);
            extensionMapField = resolveDeclaredField(
                    RuntimeValues.EXTENSION_MAP_CLASS_NAME,
                    RuntimeValues.EXTENSION_MAP_FIELD_NAME);
            extensionMapIteratorMethod = resolveMethod(RuntimeValues.EXTENSION_MAP_ITERATOR_METHOD);
        }
    }

    private interface ResponseVisitor {
        boolean visit(Object value) throws ReflectiveOperationException;
    }

    private static final class LibraryState {
        private final List<LibraryPlaylist> playlists = new ArrayList<>();
        private final Set<String> seenBrowseIds = new HashSet<>();
    }

    private static final class PlayablePlaylist {
        private final String normalizedTitle;
        private final MediaBrowserCompat.MediaItem item;

        private PlayablePlaylist(
                String normalizedTitle,
                MediaBrowserCompat.MediaItem item) {
            this.normalizedTitle = normalizedTitle;
            this.item = item;
        }
    }

    private static final class LibraryPlaylist {
        private final String browseId;
        private final String playlistId;
        private final String title;
        private final String subtitle;
        private final Uri artwork;

        private LibraryPlaylist(
                String browseId, String playlistId, String title, String subtitle, Uri artwork) {
            this.browseId = browseId;
            this.playlistId = playlistId;
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
        }
    }

    private static final class PlaybackTemplate {
        private final String mediaId;
        private final String playlistId;

        private PlaybackTemplate(String mediaId, String playlistId) {
            this.mediaId = mediaId;
            this.playlistId = playlistId;
        }
    }

    private static final class ProtobufRewrite {
        private final byte[] message;
        private final int replacements;
        private final boolean valid;

        private ProtobufRewrite(byte[] message, int replacements, boolean valid) {
            this.message = message;
            this.replacements = replacements;
            this.valid = valid;
        }

        private static ProtobufRewrite invalid() {
            return new ProtobufRewrite(new byte[0], 0, false);
        }
    }

}
