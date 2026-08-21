/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
    // Android and YTM define no timeout here, so this patch uses 30 seconds.
    private static final long BROWSE_REQUEST_TIMEOUT_MILLISECONDS = 30_000;
    // Library artwork is 60-192 px, but the same CDN URL accepts a 544 px size.
    private static final int PLAYLIST_ARTWORK_SIZE_PX = 544;
    private static final Executor REQUEST_EXECUTOR = Utils::runOnBackgroundThread;
    private static final Set<String> NATIVE_PLAYLISTS_NODE_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();

    private static volatile AndroidAutoPlaylistAccess authenticatedBrowseService;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    // Reset cached state when YTM replaces its Browse service.
    public static void initialize(AndroidAutoPlaylistAccess service) {
        if (service == null) return;
        if (authenticatedBrowseService != service) NATIVE_PLAYLISTS_NODE_MEDIA_IDS.clear();
        authenticatedBrowseService = service;
        Logger.printDebug(() -> "Authenticated Browse service ready: " +
                service.getClass().getName());
    }

    public static boolean handlePlaylistsNode(Object loadResult) {
        try {
            if (!isReady() || !isNativePlaylistsNode(loadResult)) return false;
            loadAndroidAutoPlaylists().thenAccept(items -> deliver(loadResult, items));
            return true;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not start Android Auto playlist request", error);
            return false;
        }
    }

    public static void rememberNativePlaylistsMediaId(
            String mediaId, CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE)
                .contentEquals(title)) return;
        if (mediaId != null) NATIVE_PLAYLISTS_NODE_MEDIA_IDS.add(mediaId);
    }

    private static CompletableFuture<List<MediaBrowserCompat.MediaItem>>
            loadAndroidAutoPlaylists() {
        return requestBrowse(LIBRARY_BROWSE_ID)
                .thenApply(RestoreAndroidAutoPlaylistsPatch::extractLibraryPlaylists)
                .thenApply(RestoreAndroidAutoPlaylistsPatch::createAndroidAutoPlaylistItems)
                .exceptionally(error -> {
                    Logger.printException(() -> "Library Browse request failed", error);
                    // Android Auto still needs a result when the request fails.
                    return Collections.emptyList();
                });
    }

    private static void deliver(
            Object loadResult, List<MediaBrowserCompat.MediaItem> items) {
        try {
            authenticatedBrowseService.patch_sendResult(loadResult, items);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", error);
        }
    }

    private static CompletableFuture<Object> requestBrowse(
            String browseId) {
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        ListenableFuture<?> browseRequest =
                authenticatedBrowseService.patch_requestBrowse(browseId, REQUEST_EXECUTOR);
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
        // Complete timed-out requests so Android Auto is not left waiting.
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
            for (Object content : authenticatedBrowseService.patch_getContents(response)) {
                Object section = authenticatedBrowseService.patch_getSection(content);
                if (section == null) continue;
                for (Iterable<?> items :
                        authenticatedBrowseService.patch_getItemGroups(section)) {
                    appendSectionPlaylists(items, state);
                }
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException("Could not map Library response", error);
        }
        Logger.printDebug(() -> "Mapped Library playlists: " + state.playlists.size());
        return state.playlists;
    }

    private static void appendSectionPlaylists(
            Iterable<?> items, LibraryState state) {
        for (Object item : items) {
            Iterable<?> renderers = authenticatedBrowseService.patch_getRenderers(item);
            if (renderers == null) continue;
            for (Object renderer : renderers) {
                if (!authenticatedBrowseService.patch_isResponsiveRenderer(renderer)) continue;
                try {
                    appendLibraryPlaylist(renderer, state);
                } catch (RuntimeException error) {
                    Logger.printException(() -> "Library playlist skipped", error);
                }
            }
        }
    }

    private static void appendLibraryPlaylist(
            Object renderer, LibraryState state) {
        String browseId = responsiveRendererBrowseId(renderer);
        if (browseId == null || state.seenBrowseIds.contains(browseId)) return;
        // Episodes for Later uses VLSE, which has no play command, so hide it.
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(browseId)) return;

        String title = responsiveRendererTitle(renderer);
        if (title.isEmpty()) return;
        state.seenBrowseIds.add(browseId);
        state.playlists.add(new LibraryPlaylist(
                browseId.substring(YTM_COLLECTION_BROWSE_ID_PREFIX.length()),
                title,
                optionalResponsiveRendererSubtitle(renderer),
                optionalResponsiveRendererArtwork(renderer)));
    }

    private static String responsiveRendererBrowseId(
            Object renderer) {
        String playlistBrowseId = null;
        for (Object endpoint : new Object[] {
                authenticatedBrowseService.patch_getFirstEndpoint(renderer),
                authenticatedBrowseService.patch_getSecondEndpoint(renderer)}) {
            if (endpoint == null) continue;
            String browseId = authenticatedBrowseService.patch_getBrowseId(endpoint);
            if (browseId == null || !browseId.startsWith(YTM_COLLECTION_BROWSE_ID_PREFIX)) continue;
            if (playlistBrowseId != null && !playlistBrowseId.equals(browseId)) return null;
            playlistBrowseId = browseId;
        }
        return playlistBrowseId;
    }

    private static List<MediaBrowserCompat.MediaItem> createAndroidAutoPlaylistItems(
            List<LibraryPlaylist> playlists) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>(playlists.size());
        for (LibraryPlaylist playlist : playlists) {
            String mediaId = authenticatedBrowseService.patch_playlistMediaId(playlist.playlistId);
            if (mediaId == null || mediaId.isEmpty()) {
                throw new IllegalStateException("Playlist did not produce a playback media ID");
            }
            items.add(createAndroidAutoPlaylistItem(
                    mediaId,
                    playlist.title,
                    playlist.subtitle,
                    playlist.artwork));
        }
        return items;
    }

    private static String responsiveRendererTitle(Object renderer) {
        CharSequence title = authenticatedBrowseService.patch_getTitle(renderer);
        return title == null ? "" : title.toString();
    }

    private static String optionalResponsiveRendererSubtitle(Object renderer) {
        try {
            CharSequence subtitle = authenticatedBrowseService.patch_getSubtitle(renderer);
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Uri optionalResponsiveRendererArtwork(Object renderer) {
        try {
            Iterable<?> artworkUrls = authenticatedBrowseService.patch_getArtworkUrls(renderer);
            if (artworkUrls == null) return null;
            for (Object value : artworkUrls) {
                String candidate = (String) value;
                if (candidate.startsWith("https://")) return playlistArtworkUri(candidate);
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MediaBrowserCompat.MediaItem createAndroidAutoPlaylistItem(
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

    private static boolean isNativePlaylistsNode(Object loadResult) {
        String mediaId = mediaId(loadResult);
        return mediaId != null && NATIVE_PLAYLISTS_NODE_MEDIA_IDS.contains(mediaId);
    }

    private static boolean isReady() {
        return authenticatedBrowseService != null;
    }

    private static String mediaId(Object loadResult) {
        return authenticatedBrowseService.patch_getMediaId(loadResult);
    }

    public interface AndroidAutoPlaylistAccess {
        ListenableFuture<?> patch_requestBrowse(String browseId, Executor executor);
        String patch_playlistMediaId(String playlistId);
        Iterable<?> patch_getContents(Object response);
        Object patch_getSection(Object content);
        Iterable<?>[] patch_getItemGroups(Object section);
        Iterable<?> patch_getRenderers(Object item);
        boolean patch_isResponsiveRenderer(Object value);
        Object patch_getFirstEndpoint(Object renderer);
        Object patch_getSecondEndpoint(Object renderer);
        Iterable<?> patch_getArtworkUrls(Object renderer);
        CharSequence patch_getTitle(Object renderer);
        CharSequence patch_getSubtitle(Object renderer);
        String patch_getBrowseId(Object endpoint);
        String patch_getMediaId(Object loadResult);
        void patch_sendResult(Object loadResult, List<MediaBrowserCompat.MediaItem> items);
    }

    private static final class LibraryState {
        private final List<LibraryPlaylist> playlists = new ArrayList<>();
        private final Set<String> seenBrowseIds = new HashSet<>();
    }

    private static final class LibraryPlaylist {
        private final String playlistId;
        private final String title;
        private final String subtitle;
        private final Uri artwork;

        private LibraryPlaylist(
                String playlistId, String title, String subtitle, Uri artwork) {
            this.playlistId = playlistId;
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
        }
    }

}
