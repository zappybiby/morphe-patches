/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Fills Android Auto's Playlists folder from the Library shown in YTM.
 *
 * <p>The Library supplies playlist titles and artwork. Opening each playlist through YTM's
 * browse service supplies its Play command, which Android Auto needs to start playback.
 */
@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;

    // Resolving 33 playlists took over a minute; stalled requests stayed pending for two minutes.
    private static final int LOAD_TIMEOUT_MILLISECONDS = 120_000;
    // A playlist named Playlists also matches, but playing it does not request folder contents.
    private static final Set<String> PLAYLISTS_TITLE_MATCH_MEDIA_IDS = ConcurrentHashMap.newKeySet();

    @Nullable
    private static volatile BrowseService browseService;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /** Injection point. Reuses the browse service created by YTM's Android Auto service. */
    public static void setBrowseService(@NonNull BrowseService service) {
        browseService = service;
        Logger.printDebug(() -> "YTM browse service ready: " + service.getClass().getName());
    }

    /** Injection point. Finds the Playlists folder using its translated title. */
    public static void rememberPlaylistsTitleMatch(
            @Nullable String mediaId, @Nullable CharSequence title) {
        if (title == null || !ResourceUtils.getString("library_playlists_shelf_title")
                .contentEquals(title)) return;
        if (mediaId != null) PLAYLISTS_TITLE_MATCH_MEDIA_IDS.add(mediaId);
    }

    /**
     * Injection point. YTM has already detached its Android Auto result, allowing an async reply.
     *
     * @return true if this patch handles the request; false to let YTM handle it.
     */
    public static boolean handlePlaylistsRequest(@NonNull AndroidAutoRequest request) {
        try {
            BrowseService service = browseService;
            if (service == null) return false;
            String mediaId = request.patch_getRequestedMediaId();
            if (mediaId == null || !PLAYLISTS_TITLE_MATCH_MEDIA_IDS.contains(mediaId)) return false;

            new PlaylistLoad(service, request).start();
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto Playlists request", ex);
            return false;
        }
    }

    /** Loads every Library page, then resolves playback for each playlist, preserving Library order. */
    private static final class PlaylistLoad {
        // Keep this service even if YTM recreates its Android Auto service during the load.
        private final BrowseService service;
        private final AndroidAutoRequest request;
        private final Library library = new Library();

        // Playlist callbacks and the timeout share these fields under this object's lock.
        private MediaItem[] results = new MediaItem[0];
        private int pendingPlaylists;
        private boolean delivered;

        PlaylistLoad(BrowseService service, AndroidAutoRequest request) {
            this.service = service;
            this.request = request;
        }

        void start() {
            Utils.runOnMainThreadDelayed(this::deliver, LOAD_TIMEOUT_MILLISECONDS);
            requestLibraryPage(null);
        }

        private void requestLibraryPage(@Nullable Object continuation) {
            ListenableFuture<BrowseResponse> response = continuation == null
                    ? service.patch_browse(LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR)
                    : service.patch_continueBrowse(continuation, BACKGROUND_EXECUTOR);
            response.addListener(
                    () -> onLibraryPage(response, continuation != null), BACKGROUND_EXECUTOR);
        }

        private void onLibraryPage(ListenableFuture<BrowseResponse> response, boolean isContinuation) {
            try {
                Object nextPage = library.readPage(response.get(), isContinuation);
                if (nextPage != null) {
                    requestLibraryPage(nextPage);
                } else {
                    requestPlaybackIds();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM Library request interrupted", ex);
                deliver();
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM Library request failed", ex);
                deliver();
            }
        }

        private void requestPlaybackIds() {
            if (library.playlists.isEmpty()) {
                deliver();
                return;
            }

            synchronized (this) {
                results = new MediaItem[library.playlists.size()];
                pendingPlaylists = results.length;
            }
            // YTM schedules these requests through its own executor and Cronet.
            for (int index = 0; index < library.playlists.size(); index++) {
                requestPlaybackId(index, library.playlists.get(index));
            }
        }

        private void requestPlaybackId(int index, Playlist playlist) {
            try {
                ListenableFuture<BrowseResponse> response =
                        service.patch_browse(playlist.browseId, BACKGROUND_EXECUTOR);
                response.addListener(() -> onPlaylistResponse(index, playlist, response),
                        BACKGROUND_EXECUTOR);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not request YTM playlist: " + playlist.browseId, ex);
                finishPlaylist(index, null);
            }
        }

        private void onPlaylistResponse(
                int index, Playlist playlist, ListenableFuture<BrowseResponse> response) {
            MediaItem item = null;
            try {
                BrowseResponse contents = response.get();
                // Liked Music has no Play button; its first playable song starts the queue.
                String playMediaId = LIKED_MUSIC_BROWSE_ID.equals(playlist.browseId)
                        ? Library.firstSongMediaId(contents)
                        : contents.patch_getPlayMediaId();
                if (playMediaId != null) item = playlist.toMediaItem(playMediaId);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM playlist request interrupted: " + playlist.browseId, ex);
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM playlist request failed: " + playlist.browseId, ex);
            } finally {
                finishPlaylist(index, item);
            }
        }

        private void finishPlaylist(int index, @Nullable MediaItem item) {
            synchronized (this) {
                // Responses can arrive in any order; each playlist keeps its Library position.
                results[index] = item;
                if (--pendingPlaylists != 0) return;
            }
            deliver();
        }

        private void deliver() {
            List<MediaItem> playable = new ArrayList<>();
            synchronized (this) {
                if (delivered) return;
                delivered = true;
                // The timeout returns the completed items. Later responses cannot change that list.
                for (MediaItem item : results) {
                    if (item != null) playable.add(item);
                }
            }
            try {
                request.patch_sendPlaylists(playable);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
            }
        }
    }

    /** Reads playlist metadata from YTM's Library responses, discarding other Library content. */
    private static final class Library {
        private final List<Playlist> playlists = new ArrayList<>();
        private final Set<String> seenBrowseIds = new HashSet<>();

        @Nullable
        Object readPage(BrowseResponse response, boolean isContinuation) {
            return isContinuation ? readContinuation(response) : readInitialPage(response);
        }

        private Object readInitialPage(BrowseResponse libraryResponse) {
            Object continuationAction = null;
            for (BrowseTab tab : libraryResponse.patch_getTabs()) {
                SectionList sections = tab.patch_getSectionList();
                if (sections == null) continue;
                for (Object content : sections.patch_getContents()) {
                    if (!(content instanceof Grid)) continue;
                    Grid grid = (Grid) content;
                    readGrid(grid);
                    if (continuationAction == null) {
                        continuationAction = firstContinuation(grid);
                    }
                }
            }
            Logger.printDebug(() -> "Found playlists in Library: " + playlists.size());
            return continuationAction;
        }

        private Object readContinuation(BrowseResponse libraryResponse) {
            Grid grid = libraryResponse.patch_getContinuationGrid();
            if (grid == null) return null;
            readGrid(grid);
            Logger.printDebug(() -> "Found playlists in Library: " + playlists.size());
            return firstContinuation(grid);
        }

        private void readGrid(Grid grid) {
            for (Object item : grid.patch_getItems()) {
                if (!(item instanceof PlaylistOrSong)) continue;
                try {
                    addPlaylist((PlaylistOrSong) item);
                } catch (RuntimeException ex) {
                    Logger.printException(() -> "Could not read a Library item", ex);
                }
            }
        }

        @Nullable
        private static Object firstContinuation(Grid grid) {
            // NEXT and RELOAD can both fetch another Library response.
            Iterator<?> actions = grid.patch_getContinuationActions().iterator();
            return actions.hasNext() ? actions.next() : null;
        }

        private void addPlaylist(PlaylistOrSong item) {
            String browseId = item.patch_getBrowseId();
            if (browseId == null) return;
            // Episodes for Later (VLSE) has no Play button.
            if (EPISODES_FOR_LATER_BROWSE_ID.equals(browseId)) return;

            CharSequence titleText = item.patch_getTitle();
            String title = titleText == null ? "" : titleText.toString();
            if (title.isEmpty()) return;
            if (!seenBrowseIds.add(browseId)) return;
            // Subtitle and artwork are optional for playback.
            playlists.add(new Playlist(
                    browseId,
                    title,
                    subtitleOrEmpty(item),
                    artworkUriOrNull(item)));
        }

        private static String subtitleOrEmpty(PlaylistOrSong playlist) {
            try {
                CharSequence subtitle = playlist.patch_getSubtitle();
                return subtitle == null ? "" : subtitle.toString();
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        private static Uri artworkUriOrNull(PlaylistOrSong playlist) {
            try {
                return playlist.patch_getArtworkUri();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static String firstSongMediaId(BrowseResponse playlistResponse) {
            for (BrowseTab tab : playlistResponse.patch_getTabs()) {
                SectionList sections = tab.patch_getSectionList();
                if (sections == null) continue;
                for (Object content : sections.patch_getContents()) {
                    if (!(content instanceof SongList)) continue;
                    for (PlaylistOrSong song :
                            ((SongList) content).patch_getSongs()) {
                        String playableMediaId = song.patch_getPlayMediaId();
                        if (playableMediaId != null) return playableMediaId;
                    }
                }
            }
            return null;
        }
    }

    private static final class Playlist {
        private final String browseId;
        private final String title;
        private final String subtitle;
        private final Uri artwork;

        Playlist(String browseId, String title, String subtitle, Uri artwork) {
            this.browseId = browseId;
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
        }

        MediaItem toMediaItem(String playMediaId) {
            MediaDescriptionCompat description = new MediaDescriptionCompat(
                    playMediaId, title, subtitle, null, null, artwork, null, null);
            return new MediaItem(description, MediaItem.FLAG_PLAYABLE);
        }
    }

    // The Kotlin patch adds these interfaces to YTM's classes.

    /** YTM's existing requests for the Library and individual playlist screens. */
    public interface BrowseService {
        @NonNull ListenableFuture<BrowseResponse> patch_browse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<BrowseResponse> patch_continueBrowse(
                @NonNull Object continuationAction, @NonNull Executor executor);
    }

    /**
     * YTM wraps the initial Library and playlist contents in tabs, each containing a list of
     * sections. A section holds either a Library grid or a playlist's songs. These are response
     * containers, not necessarily visible tabs or separate screens. Library pagination returns
     * a grid directly.
     */
    public interface BrowseResponse {
        // Initial Library and opened-playlist responses.
        @NonNull Iterable<BrowseTab> patch_getTabs();
        // Pagination responses.
        @Nullable Grid patch_getContinuationGrid();
        // Opened-playlist responses.
        @Nullable String patch_getPlayMediaId();
    }

    public interface BrowseTab {
        @Nullable SectionList patch_getSectionList();
    }

    public interface SectionList {
        @NonNull Iterable<?> patch_getContents();
    }

    public interface Grid {
        // The phone Library grid mixes playlists with artists, podcasts, and other content.
        @NonNull Iterable<?> patch_getItems();
        @NonNull Iterable<?> patch_getContinuationActions();
    }

    // Songs below an opened playlist's header.
    public interface SongList {
        @NonNull Iterable<PlaylistOrSong> patch_getSongs();
    }

    // Carries the requested Playlists folder ID and the playlist list returned to Android Auto.
    public interface AndroidAutoRequest {
        @Nullable String patch_getRequestedMediaId();
        void patch_sendPlaylists(@NonNull List<MediaItem> playlists);
    }

    /** YTM uses the same message for Library playlists and songs within a playlist. */
    public interface PlaylistOrSong {
        /** VL-prefixed ID for fetching playlist contents; null for other Library items. */
        @Nullable String patch_getBrowseId();
        /** Encoded Play command used as an Android Auto media ID. */
        @Nullable String patch_getPlayMediaId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }
}
