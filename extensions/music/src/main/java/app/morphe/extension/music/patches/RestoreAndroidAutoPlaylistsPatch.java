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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String PLAYLISTS_TITLE_RESOURCE = "library_playlists_shelf_title";
    private static final String YTM_COLLECTION_BROWSE_ID_PREFIX = "VL";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
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
            if (authenticatedBrowseService == null || !isNativePlaylistsNode(loadResult)) return false;
            loadAndroidAutoPlaylists(loadResult);
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

    private static void loadAndroidAutoPlaylists(Object loadResult) {
        LibraryState state = new LibraryState();
        loadAndroidAutoPlaylists(loadResult, state,
                authenticatedBrowseService.patch_requestBrowse(
                        LIBRARY_BROWSE_ID, REQUEST_EXECUTOR), false);
    }

    private static void loadAndroidAutoPlaylists(
            Object loadResult, LibraryState state, ListenableFuture<?> browseRequest,
            boolean continuationRequest) {
        browseRequest.addListener(() -> {
            try {
                Object response = browseRequest.get();
                Object continuation = continuationRequest
                        ? appendContinuationPlaylists(response, state)
                        : appendLibraryPlaylists(response, state);
                if (continuation != null) {
                    loadAndroidAutoPlaylists(loadResult, state,
                            authenticatedBrowseService.patch_requestContinuation(
                                    continuation, REQUEST_EXECUTOR), true);
                    return;
                }
                loadAndroidAutoPlaylistItems(loadResult, state.playlists);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "Library Browse request interrupted", error);
                deliver(loadResult, Collections.emptyList());
            } catch (ExecutionException | RuntimeException error) {
                Logger.printException(() -> "Library Browse request failed", error);
                deliver(loadResult, Collections.emptyList());
            }
        }, REQUEST_EXECUTOR);
    }

    private static void deliver(
            Object loadResult, List<MediaBrowserCompat.MediaItem> items) {
        try {
            authenticatedBrowseService.patch_sendResult(loadResult, items);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", error);
        }
    }

    private static Object appendLibraryPlaylists(
            Object response, LibraryState state) {
        Object nextContinuation = null;
        try {
            for (Object content : authenticatedBrowseService.patch_getContents(response)) {
                Object section = authenticatedBrowseService.patch_getSection(content);
                if (section == null) continue;
                for (Iterable<?> items :
                        authenticatedBrowseService.patch_getItemGroups(section)) {
                    nextContinuation = appendSectionPlaylists(
                            items, state, nextContinuation);
                }
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException("Could not map Library response", error);
        }
        Logger.printDebug(() -> "Mapped Library playlists: " + state.playlists.size());
        return nextContinuation;
    }

    private static Object appendContinuationPlaylists(
            Object response, LibraryState state) {
        Object content = authenticatedBrowseService.patch_getContinuationContent(response);
        Object nextContinuation = content == null ? null : appendSectionPlaylists(
                Collections.singletonList(content), state, null);
        Logger.printDebug(() -> "Mapped Library playlists: " + state.playlists.size());
        return nextContinuation;
    }

    private static Object appendSectionPlaylists(
            Iterable<?> items, LibraryState state, Object nextContinuation) {
        for (Object item : items) {
            if (nextContinuation == null) {
                Iterable<?> continuations =
                        authenticatedBrowseService.patch_getContinuations(item);
                if (continuations != null) {
                    Iterator<?> iterator = continuations.iterator();
                    if (iterator.hasNext()) nextContinuation = iterator.next();
                }
            }

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
        return nextContinuation;
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
                browseId,
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

    private static void loadAndroidAutoPlaylistItems(
            Object loadResult, List<LibraryPlaylist> playlists) {
        if (playlists.isEmpty()) {
            deliver(loadResult, Collections.emptyList());
            return;
        }

        MediaBrowserCompat.MediaItem[] items = new MediaBrowserCompat.MediaItem[playlists.size()];
        AtomicInteger remaining = new AtomicInteger(playlists.size());
        // Liked Music has no playlist-page play endpoint, so reuse an ordinary endpoint container.
        String likedMusicTemplateBrowseId = null;
        for (LibraryPlaylist playlist : playlists) {
            if (!LIKED_MUSIC_BROWSE_ID.equals(playlist.browseId)) {
                likedMusicTemplateBrowseId = playlist.browseId;
                break;
            }
        }
        for (int index = 0; index < playlists.size(); index++) {
            int itemIndex = index;
            LibraryPlaylist playlist = playlists.get(index);
            String playbackBrowseId = LIKED_MUSIC_BROWSE_ID.equals(playlist.browseId) &&
                    likedMusicTemplateBrowseId != null
                    ? likedMusicTemplateBrowseId
                    : playlist.browseId;
            ListenableFuture<?> browseRequest = authenticatedBrowseService.patch_requestBrowse(
                    playbackBrowseId, REQUEST_EXECUTOR);
            browseRequest.addListener(() -> {
                try {
                    Object response = browseRequest.get();
                    String mediaId = authenticatedBrowseService.patch_getPlaylistPlaybackMediaId(
                            response, playlist.playlistId);
                    if (mediaId != null && !mediaId.isEmpty()) {
                        items[itemIndex] = createAndroidAutoPlaylistItem(
                                mediaId, playlist.title, playlist.subtitle, playlist.artwork);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    Logger.printException(() -> "Playlist Browse request interrupted", error);
                } catch (ExecutionException | RuntimeException error) {
                    Logger.printException(() -> "Playlist Browse request failed", error);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        List<MediaBrowserCompat.MediaItem> playableItems =
                                new ArrayList<>(playlists.size());
                        for (MediaBrowserCompat.MediaItem item : items) {
                            if (item != null) playableItems.add(item);
                        }
                        deliver(loadResult, playableItems);
                    }
                }
            }, REQUEST_EXECUTOR);
        }
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
            return authenticatedBrowseService.patch_getArtwork(renderer);
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

    private static boolean isNativePlaylistsNode(Object loadResult) {
        String mediaId = authenticatedBrowseService.patch_getMediaId(loadResult);
        return mediaId != null && NATIVE_PLAYLISTS_NODE_MEDIA_IDS.contains(mediaId);
    }

    public interface AndroidAutoPlaylistAccess {
        ListenableFuture<?> patch_requestBrowse(String browseId, Executor executor);
        ListenableFuture<?> patch_requestContinuation(Object continuation, Executor executor);
        String patch_getPlaylistPlaybackMediaId(Object response, String playlistId);
        Iterable<?> patch_getContents(Object response);
        Object patch_getContinuationContent(Object response);
        Object patch_getSection(Object content);
        Iterable<?>[] patch_getItemGroups(Object section);
        Iterable<?> patch_getRenderers(Object item);
        Iterable<?> patch_getContinuations(Object item);
        boolean patch_isResponsiveRenderer(Object value);
        Object patch_getFirstEndpoint(Object renderer);
        Object patch_getSecondEndpoint(Object renderer);
        Uri patch_getArtwork(Object renderer);
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
        private final String browseId;
        private final String playlistId;
        private final String title;
        private final String subtitle;
        private final Uri artwork;

        private LibraryPlaylist(
                String browseId, String title, String subtitle, Uri artwork) {
            this.browseId = browseId;
            this.playlistId = browseId.substring(YTM_COLLECTION_BROWSE_ID_PREFIX.length());
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
        }
    }

}
