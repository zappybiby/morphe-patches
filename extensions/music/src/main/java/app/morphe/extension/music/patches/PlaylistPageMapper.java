/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;

final class PlaylistPageMapper {
    private static final String PLAYABLE_CONTENT_STYLE_EXTRA =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final int CONTENT_STYLE_LIST = 1;

    // These field paths are the same in every supported 9.x version.
    private static final String[] CONTINUATION_SECTION_FIELD_PATH = {"a", "i"};
    private static final String[] TAB_LIST_FIELD_PATH = {"a", "f", "c", "b"};
    private static final String[] TAB_CONTENT_FIELD_PATH = {"c", "i", "c"};
    private static final String MUSIC_SHELF_ROWS_FIELD_NAME = "f";
    private static final String MUSIC_SHELF_CONTINUATIONS_FIELD_NAME = "k";
    private static final String[] CONTINUATION_TOKEN_FIELD_PATH = {"c", "e"};
    private static final String RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME = "c";
    private static final String RESPONSIVE_RENDERER_TITLE_FIELD_NAME = "g";
    private static final String RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME = "h";
    private static final String TEXT_RUNS_FIELD_NAME = "c";
    private static final String TEXT_DIRECT_VALUE_FIELD_NAME = "d";
    private static final String TEXT_RUN_VALUE_FIELD_NAME = "c";
    private static final String EXTENSION_MAP_FIELD_NAME = "j";
    private static final String EXTENSION_ITERATOR_METHOD_NAME = "e";
    // YTM already requests 544 px artwork for playlist tiles, but only 120 px for song rows.
    private static final int ANDROID_AUTO_PLAYLIST_ROW_ARTWORK_SIZE_PX = 544;
    // Google image URLs encode their dimensions as w###, h###, or s###.
    private static final String IMAGE_DIMENSION_MARKERS = "whs";
    private static final String IMAGE_DIMENSION_PREFIXES = "=-/";

    private static volatile String responsiveRendererClassName;
    private static volatile String musicShelfRendererClassName;
    private static volatile String responsiveRendererEndpointFieldName;
    private static volatile String mediaIdHelperClassName;
    private static volatile String mediaIdHelperMethodName;
    private static volatile String browseEndpointClassName;
    private static volatile String browseIdFieldName;
    private static volatile Method endpointMediaIdMethod;

    private PlaylistPageMapper() {
    }

    static void configure(
            String responsiveRendererClass,
            String musicShelfRendererClass,
            String responsiveRendererEndpointField,
            String mediaIdHelperClass,
            String mediaIdHelperMethod,
            String browseEndpointClass,
            String browseIdField) {
        responsiveRendererClassName = responsiveRendererClass;
        musicShelfRendererClassName = musicShelfRendererClass;
        responsiveRendererEndpointFieldName = responsiveRendererEndpointField;
        mediaIdHelperClassName = mediaIdHelperClass;
        mediaIdHelperMethodName = mediaIdHelperMethod;
        browseEndpointClassName = browseEndpointClass;
        browseIdFieldName = browseIdField;
        endpointMediaIdMethod = null;
    }

    static boolean isResponsiveRenderer(Object value) {
        return value != null && value.getClass().getName().equals(responsiveRendererClassName);
    }

    static Object responsiveRendererEndpoint(Object renderer) throws Exception {
        return readField(renderer, responsiveRendererEndpointFieldName);
    }

    static String responsiveRendererTitle(Object renderer) throws Exception {
        return renderText(readField(renderer, RESPONSIVE_RENDERER_TITLE_FIELD_NAME));
    }

    static String responsiveRendererSubtitle(Object renderer) throws Exception {
        return renderText(readField(renderer, RESPONSIVE_RENDERER_SUBTITLE_FIELD_NAME));
    }

    static Uri responsiveRendererArtwork(Object renderer) throws Exception {
        return findArtworkUri(readField(renderer, RESPONSIVE_RENDERER_ARTWORK_FIELD_NAME));
    }

    static String findBrowseId(Object endpoint) {
        if (endpoint == null) return null;
        String[] foundBrowseId = new String[1];
        ObjectGraphState state = new ObjectGraphState();
        try {
            walkObjectGraph(endpoint, state, candidate -> {
                if (!candidate.getClass().getName().equals(browseEndpointClassName)) return false;
                Object browseId = readField(candidate, browseIdFieldName);
                if (browseId instanceof String && !((String) browseId).isEmpty()) {
                    foundBrowseId[0] = (String) browseId;
                    state.stopped = true;
                }
                return true;
            });
        } catch (Throwable ignored) {
        }
        return foundBrowseId[0];
    }

    static String appendPlaylistPage(Object response, List<Object> items, Set<String> mediaIds) {
        PlaylistPageState state = new PlaylistPageState(items, mediaIds);
        try {
            appendPlaylistContents(response, state);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not inspect playlist page", error);
        }
        Logger.printDebug(() -> "Mapped playlist page: " + items.size()
                + " items; continuation=" + (state.continuation != null));
        return state.continuation;
    }

    private static void appendPlaylistContents(Object response, PlaylistPageState state)
            throws Exception {
        int itemCountBeforeMapping = state.items.size();
        // Start with the known playlist sections so unrelated renderers are not treated as tracks.
        // If YouTube Music changes the layout, fall back to walking the full response.
        Iterator<?> continuationEntries = extensionEntries(
                readFieldPath(response, CONTINUATION_SECTION_FIELD_PATH));
        if (continuationEntries != null) {
            while (continuationEntries.hasNext()) {
                Object entry = continuationEntries.next();
                if (entry instanceof Map.Entry<?, ?>) {
                    appendPlaylistRenderers(((Map.Entry<?, ?>) entry).getValue(), state);
                }
            }
        }

        Object tabList = readFieldPath(response, TAB_LIST_FIELD_PATH);
        if (tabList instanceof Iterable<?>) {
            for (Object tab : (Iterable<?>) tabList) {
                appendPlaylistRenderers(readFieldPath(tab, TAB_CONTENT_FIELD_PATH), state);
            }
        }
        if (state.items.size() == itemCountBeforeMapping) {
            Logger.printDebug(() -> "Using fallback playlist response traversal");
            appendPlaylistRenderers(response, state);
        }
    }

    private static void appendPlaylistRenderers(Object value, PlaylistPageState state)
            throws Exception {
        walkObjectGraph(value, state.objectGraphState, candidate -> {
            if (isResponsiveRenderer(candidate)) {
                appendPlayableItem(candidate, state);
                return true;
            }
            if (candidate.getClass().getName().equals(musicShelfRendererClassName)) {
                captureShelfContinuation(candidate, state);
                Object rows = readField(candidate, MUSIC_SHELF_ROWS_FIELD_NAME);
                if (rows instanceof Iterable<?>) {
                    for (Object row : (Iterable<?>) rows) appendPlaylistRenderers(row, state);
                }
                return true;
            }
            return false;
        });
    }

    private static void captureShelfContinuation(Object shelf, PlaylistPageState state) {
        if (state.continuation != null) return;
        try {
            Object continuations = readField(shelf, MUSIC_SHELF_CONTINUATIONS_FIELD_NAME);
            if (!(continuations instanceof Iterable<?>)) return;
            for (Object wrapper : (Iterable<?>) continuations) {
                Object token = readFieldPath(wrapper, CONTINUATION_TOKEN_FIELD_PATH);
                if (token instanceof String && !((String) token).isEmpty()) {
                    state.continuation = (String) token;
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendPlayableItem(Object renderer, PlaylistPageState state) {
        try {
            Object endpoint = responsiveRendererEndpoint(renderer);
            if (endpoint == null) return;
            Method getMediaId = endpointMediaIdMethod;
            if (getMediaId == null) {
                Class<?> mediaIdHelper = Class.forName(
                        mediaIdHelperClassName, false, PlaylistPageMapper.class.getClassLoader());
                getMediaId = mediaIdHelper.getDeclaredMethod(
                        mediaIdHelperMethodName, endpoint.getClass());
                getMediaId.setAccessible(true);
                endpointMediaIdMethod = getMediaId;
            }
            Object idValue = getMediaId.invoke(null, endpoint);
            if (!(idValue instanceof String)) return;
            // Editing actions can have a watch endpoint, but playable rows also have a video ID.
            if (!RestoreAndroidAutoPlaylistsPatch.hasPlayableVideoId((String) idValue)) return;
            String mediaId = RestoreAndroidAutoPlaylistsPatch.removeOptionalPlayerConfigFromMediaId(
                    (String) idValue);
            if (mediaId.isEmpty() || state.mediaIds.contains(mediaId)) return;

            String title = responsiveRendererTitle(renderer);
            if (title.isEmpty()) return;
            String subtitle = responsiveRendererSubtitle(renderer);
            Uri iconUri = responsiveRendererArtwork(renderer);
            Object item = createMediaItem(mediaId, title, subtitle, iconUri, false);
            if (!state.mediaIds.add(mediaId)) return;
            state.items.add(item);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not map playlist row", error);
        }
    }

    static Object createBrowsableItem(String mediaId, String title, String subtitle, Uri iconUri) {
        return createMediaItem(mediaId, title, subtitle, iconUri, true);
    }

    private static Object createMediaItem(
            String mediaId,
            String title,
            String subtitle,
            Uri iconUri,
            boolean browsable) {
        Bundle extras = new Bundle();
        if (browsable && AndroidAutoPlaylistSettings.LIST_VIEW.get()) {
            extras.putInt(PLAYABLE_CONTENT_STYLE_EXTRA, CONTENT_STYLE_LIST);
        }
        MediaDescriptionCompat mediaDescription = new MediaDescriptionCompat(
                mediaId, title, subtitle, null, null, iconUri, extras, null);
        int flags = browsable
                ? MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                : MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
        return new MediaBrowserCompat.MediaItem(mediaDescription, flags);
    }

    static Uri findArtworkUri(Object value) {
        if (value == null) return null;
        ArtworkCandidate best = new ArtworkCandidate();
        try {
            walkObjectGraph(value, new ObjectGraphState(), candidate -> {
                if (candidate instanceof CharSequence) {
                    best.consider(candidate.toString());
                }
                return false;
            });
        } catch (Throwable ignored) {
        }
        return best.bestUrl == null ? null : Uri.parse(largerGoogleArtwork(best.bestUrl));
    }

    private static String largerGoogleArtwork(String url) {
        String host = Uri.parse(url).getHost();
        if (!("yt3.googleusercontent.com".equals(host)
                || "lh3.googleusercontent.com".equals(host)
                || "yt3.ggpht.com".equals(host))) return url;
        int currentSize = ArtworkCandidate.largestImageDimension(url);
        if (currentSize == 0 || currentSize >= ANDROID_AUTO_PLAYLIST_ROW_ARTWORK_SIZE_PX) return url;

        StringBuilder resizedUrl = new StringBuilder(url.length());
        for (int index = 0; index < url.length();) {
            char current = url.charAt(index);
            boolean startsDimension = index > 0
                    && IMAGE_DIMENSION_MARKERS.indexOf(current) >= 0
                    && IMAGE_DIMENSION_PREFIXES.indexOf(url.charAt(index - 1)) >= 0
                    && index + 1 < url.length()
                    && Character.isDigit(url.charAt(index + 1));
            resizedUrl.append(current);
            index++;
            if (!startsDimension) continue;
            resizedUrl.append(ANDROID_AUTO_PLAYLIST_ROW_ARTWORK_SIZE_PX);
            while (index < url.length() && Character.isDigit(url.charAt(index))) index++;
        }
        return resizedUrl.toString();
    }

    static String renderText(Object text) {
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

    static Object readField(Object instance, String name) throws Exception {
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

    static Object readFieldPath(Object instance, String[] fieldNames) throws Exception {
        Object value = instance;
        if (fieldNames == null) return null;
        for (String fieldName : fieldNames) value = readField(value, fieldName);
        return value;
    }

    static Iterator<?> extensionEntries(Object message) {
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

    static void walkObjectGraph(
            Object value, ObjectGraphState state, ObjectVisitor visitor) throws Exception {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        enqueue(value, state, pending);

        while (!pending.isEmpty() && !state.stopped) {
            Object current = pending.removeFirst();
            if (visitor.skipChildren(current)) continue;

            Class<?> valueClass = current.getClass();
            if (current instanceof CharSequence || current instanceof Number
                    || current instanceof Boolean || valueClass.isEnum()) {
                continue;
            }
            if (current instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) current) {
                    enqueue(item, state, pending);
                }
                continue;
            }

            if (!isObfuscatedYtmClass(valueClass)) continue;
            Iterator<?> extensionEntries = extensionEntries(current);
            if (extensionEntries != null) {
                while (extensionEntries.hasNext()) {
                    Object entry = extensionEntries.next();
                    if (entry instanceof Map.Entry<?, ?>) {
                        enqueue(((Map.Entry<?, ?>) entry).getValue(), state, pending);
                    }
                }
            }
            for (Class<?> owner = valueClass; owner != null && owner != Object.class;
                    owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.getType().isPrimitive()) continue;
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

    interface ObjectVisitor {
        boolean skipChildren(Object value) throws Exception;
    }

    static final class ObjectGraphState {
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private boolean stopped;
    }

    private static final class ArtworkCandidate {
        private String bestUrl;
        private boolean bestUrlUsesHttps;
        private int bestImageDimension;

        private void consider(String candidate) {
            boolean usesHttps = candidate.startsWith("https://");
            if (!usesHttps && !candidate.startsWith("http://")) return;

            int candidateDimension = largestImageDimension(candidate);
            boolean upgradesToHttps = usesHttps && !bestUrlUsesHttps;
            boolean hasLargerImage = usesHttps == bestUrlUsesHttps
                    && candidateDimension > bestImageDimension;
            boolean winsStableTieBreak = bestUrl != null
                    && usesHttps == bestUrlUsesHttps
                    && candidateDimension == bestImageDimension
                    && candidate.compareTo(bestUrl) < 0;
            if (bestUrl == null || upgradesToHttps || hasLargerImage || winsStableTieBreak) {
                bestUrl = candidate;
                bestUrlUsesHttps = usesHttps;
                bestImageDimension = candidateDimension;
            }
        }

        private static int largestImageDimension(String url) {
            int largestDimension = 0;
            for (int i = 1; i + 1 < url.length(); i++) {
                char dimensionMarker = url.charAt(i);
                if (IMAGE_DIMENSION_MARKERS.indexOf(dimensionMarker) < 0) continue;
                char markerPrefix = url.charAt(i - 1);
                if (IMAGE_DIMENSION_PREFIXES.indexOf(markerPrefix) < 0) continue;

                int dimension = 0;
                for (int index = i + 1; index < url.length(); index++) {
                    char digit = url.charAt(index);
                    if (digit < '0' || digit > '9') break;
                    if (dimension > (Integer.MAX_VALUE - (digit - '0')) / 10) {
                        dimension = Integer.MAX_VALUE;
                        break;
                    }
                    dimension = (dimension * 10) + (digit - '0');
                }
                if (dimension > largestDimension) largestDimension = dimension;
            }
            return largestDimension;
        }
    }

    private static final class PlaylistPageState {
        private final List<Object> items;
        private final Set<String> mediaIds;
        private final ObjectGraphState objectGraphState = new ObjectGraphState();
        private String continuation;

        PlaylistPageState(List<Object> items, Set<String> mediaIds) {
            this.items = items;
            this.mediaIds = mediaIds;
        }
    }

}
