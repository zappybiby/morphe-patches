/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Returns the playlists shown in YTM's phone Library when Android Auto opens Playlists.
 *
 * <p>Use the Library's playlist names and artwork to build the Android Auto list. Load a
 * playlist's contents only when selected. Each item carries a deferred media ID: a patch-specific
 * ID containing the playlist's Browse ID (VL...). Use that Browse ID to load the selected
 * playlist, then pass its playback command to YTM.
 *
 * <p>Failure behavior preserves current playback:
 * <ul>
 *   <li>If the selected playlist loads without songs, try to show the empty-playlist message.
 *       This requires an existing playback state with no error already reported by YTM.
 *   <li>Log playlist load errors and timeouts without changing playback. A missing Play-button
 *       command is handled the same way. These failures do not mean the playlist is empty.
 *   <li>Return the playlists collected so far if loading the Library fails or times out.
 *       Return an empty list if none were collected.
 * </ul>
 *
 * <p>The Kotlin patch adds the interfaces and methods below to YTM's obfuscated classes.
 */
@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String DEFERRED_PLAYLIST_MEDIA_ID_PREFIX = "morphe:aa:playlist:";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    // Limit the total time spent loading Library pages before returning the collected playlists.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 30_000;
    // Maximum wait before ignoring a late response for the selected playlist.
    private static final int SELECTED_PLAYLIST_LOAD_TIMEOUT_MILLISECONDS = 30_000;
    private static final int NO_TRACKS_NOTICE_MILLISECONDS = 8_000;
    // PlaybackStateCompat's app error code, required to display the empty-playlist message.
    private static final int APP_ERROR_CODE = 1;
    private static final String NO_TRACKS_MESSAGE_RESOURCE_NAME = "sideloaded_playlists_no_tracks";
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    // A user playlist can also be named Playlists. The title match only affects opening
    // folders; it does not change playlist playback.
    private static final Set<String> PLAYLISTS_TITLE_MATCH_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();
    // A new selection or Pause/Stop cancels pending playback. Opening a folder does not.
    private static final AtomicLong PLAY_REQUEST_GENERATION = new AtomicLong();
    private static final WeakHashMap<Object, WeakReference<PlaybackStateSession>>
            PLAYBACK_SESSIONS = new WeakHashMap<>();

    // Added to YTM's client for loading Library pages and playlist contents.
    public interface PhoneBrowseRequests {
        @NonNull ListenableFuture<BrowseResponse> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<BrowseResponse> patch_requestLibraryContinuation(
                @NonNull Object continuationAction, @NonNull Executor executor);
    }

    // Added to YTM's response object for Library pages and playlist contents.
    public interface BrowseResponse {
        // Contents of the first Library page or an opened playlist.
        @NonNull Iterable<BrowseTab> patch_getTabs();
        // Contents of later Library pages.
        @Nullable GridRenderer patch_getPaginatedLibraryGrid();
        @Nullable String patch_getHeaderPlayMediaId();
    }

    // Added to YTM's TabRenderer wrapper, which can exist without a visible tab.
    public interface BrowseTab {
        @Nullable SectionList patch_getSectionList();
    }

    // A SectionList groups parts of a page, such as Library items or a playlist's songs.
    public interface SectionList {
        @NonNull Iterable<?> patch_getContents();
    }

    // GridRenderer holds the Library's items and actions for loading more items.
    public interface GridRenderer {
        // The phone Library grid mixes playlists with artists, podcasts, and other content.
        @NonNull Iterable<?> patch_getRows();
        // NEXT loads more items; RELOAD refreshes the list.
        @NonNull Iterable<?> patch_getContinuationActions();
    }

    // Playlist contents include songs and editor buttons such as "Add a song".
    public interface OpenedPlaylistRows {
        @NonNull Iterable<SharedBrowseRow> patch_getRawRows();
    }

    // Holds the folder ID and the result object used to send its items to Android Auto.
    public interface AndroidAutoPlaylistsRequest {
        @Nullable String patch_getRequestedMediaId();
        void patch_deliverAndroidAutoPlaylists(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoPlaylists);
    }

    public interface PlaybackCallback {
        @Nullable Handler patch_getCallbackHandler();
        @Nullable PlaybackStateSession patch_getPlaybackStateSession();
    }

    // Use YTM's setter so both YTM and Android Auto receive playback-state changes.
    public interface PlaybackStateSession {
        @NonNull Object patch_getPlaybackOwner();
        @Nullable PlaybackStateCompat patch_getPlaybackState();
        void patch_setPlaybackState(@NonNull PlaybackStateCompat state);
    }

    // YTM uses the same row type for Library playlists, songs, and editor buttons.
    public interface SharedBrowseRow {
        @Nullable String patch_getPlaylistBrowseId();
        @Nullable String patch_getActionMediaId();
        // Checks the selected action for a WatchEndpoint with a nonempty video ID.
        boolean patch_hasPlayableVideoId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    // Keep each load tied to its original client if MusicBrowserService is recreated.
    @Nullable
    private static volatile PhoneBrowseRequests phoneBrowseRequests;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /**
     * Injection point. Save the client created by MusicBrowserService for Library and playlist requests.
     */
    public static void setPhoneBrowseRequests(@NonNull PhoneBrowseRequests requests) {
        phoneBrowseRequests = requests;
        Logger.printDebug(() -> "Ready to request phone Library and opened playlists: " +
                requests.getClass().getName());
    }

    /**
     * Injection point. Record IDs paired with the translated Playlists title; the folder ID varies.
     */
    public static void rememberPlaylistsTitleMatch(
            @Nullable String androidAutoMediaId, @Nullable CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE_NAME)
                .contentEquals(title)) return;
        if (androidAutoMediaId != null) PLAYLISTS_TITLE_MATCH_MEDIA_IDS.add(androidAutoMediaId);
    }

    /**
     * Injection point. Load the Playlists folder asynchronously. YTM has already called
     * MediaBrowserService.Result.detach(), allowing the result to be sent after this method returns.
     *
     * @return true if this method will send the folder's result; false to let YTM handle the request.
     *         Returning true does not mean loading has finished.
     */
    public static boolean handleAndroidAutoPlaylists(
            @NonNull AndroidAutoPlaylistsRequest androidAutoRequest) {
        try {
            PhoneBrowseRequests phoneRequests = phoneBrowseRequests;
            if (phoneRequests == null) return false;
            String requestedMediaId = androidAutoRequest.patch_getRequestedMediaId();
            if (requestedMediaId == null) return false;
            if (!PLAYLISTS_TITLE_MATCH_MEDIA_IDS.contains(requestedMediaId)) return false;
            PlaylistFolderLoad load = new PlaylistFolderLoad(phoneRequests);
            try {
                // Late responses are ignored; YTM's underlying requests are not cancelled.
                Utils.runOnMainThreadDelayed(
                        () -> deliverAndroidAutoPlaylists(androidAutoRequest, load, "timed out"),
                        ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS);
                requestPhoneLibrary(androidAutoRequest, load);
            } catch (RuntimeException ex) {
                // Finish this request here. Returning false would let YTM answer it too,
                // while the timeout is still scheduled to send our result.
                Logger.printException(() -> "Could not request YTM Library", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto Playlists request", ex);
            return false;
        }
    }

    private static void requestPhoneLibrary(
            AndroidAutoPlaylistsRequest androidAutoRequest, PlaylistFolderLoad load) {
        handleLibraryResponse(
                androidAutoRequest, load,
                load.phoneBrowseRequests.patch_requestBrowse(
                        PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR),
                RestoreAndroidAutoPlaylistsPatch::collectInitialLibraryPlaylists);
    }

    private static void requestLibraryContinuation(
            AndroidAutoPlaylistsRequest androidAutoRequest, PlaylistFolderLoad load,
            Object continuationAction) {
        handleLibraryResponse(
                androidAutoRequest, load,
                load.phoneBrowseRequests.patch_requestLibraryContinuation(
                        continuationAction, BACKGROUND_EXECUTOR),
                RestoreAndroidAutoPlaylistsPatch::collectPaginatedLibraryPlaylists);
    }

    private static void handleLibraryResponse(
            AndroidAutoPlaylistsRequest androidAutoRequest, PlaylistFolderLoad load,
            ListenableFuture<BrowseResponse> libraryResponseFuture,
            BiFunction<BrowseResponse, PlaylistFolderLoad, Object>
                    collectPlaylistsAndGetContinuation) {
        libraryResponseFuture.addListener(() -> {
            synchronized (load) {
                if (load.androidAutoResultDelivered) return;
            }
            try {
                BrowseResponse libraryResponse = libraryResponseFuture.get();
                Object continuationAction =
                        collectPlaylistsAndGetContinuation.apply(libraryResponse, load);
                synchronized (load) {
                    if (load.androidAutoResultDelivered) return;
                }
                if (continuationAction != null) {
                    requestLibraryContinuation(androidAutoRequest, load, continuationAction);
                    return;
                }
                // Load playlist contents on selection to avoid delaying this list.
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "completed");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM Library request interrupted", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "interrupted");
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM Library request failed", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
        }, BACKGROUND_EXECUTOR);
    }

    private static Object collectInitialLibraryPlaylists(
            BrowseResponse libraryResponse, PlaylistFolderLoad load) {
        Object continuationAction = null;
        for (BrowseTab tab : libraryResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof GridRenderer)) continue;
                GridRenderer gridRenderer = (GridRenderer) sectionContent;
                collectPlaylistsFromGrid(gridRenderer, load);
                if (continuationAction == null) {
                    continuationAction = firstContinuationAction(gridRenderer);
                }
            }
        }
        synchronized (load) {
            Logger.printDebug(() -> "Found playlists in phone Library: " +
                    load.libraryPlaylistRows.size());
        }
        return continuationAction;
    }

    private static Object collectPaginatedLibraryPlaylists(
            BrowseResponse libraryResponse, PlaylistFolderLoad load) {
        GridRenderer gridRenderer = libraryResponse.patch_getPaginatedLibraryGrid();
        if (gridRenderer == null) return null;
        collectPlaylistsFromGrid(gridRenderer, load);
        synchronized (load) {
            Logger.printDebug(() -> "Found playlists in phone Library: " +
                    load.libraryPlaylistRows.size());
        }
        return firstContinuationAction(gridRenderer);
    }

    private static void collectPlaylistsFromGrid(
            GridRenderer gridRenderer, PlaylistFolderLoad load) {
        for (Object gridRow : gridRenderer.patch_getRows()) {
            if (!(gridRow instanceof SharedBrowseRow)) continue;
            try {
                addLibraryPlaylistRow((SharedBrowseRow) gridRow, load);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not read a Library grid row", ex);
            }
        }
    }

    @Nullable
    private static Object firstContinuationAction(GridRenderer gridRenderer) {
        // Expect at most one NEXT action for loading more Library items.
        Iterator<?> actions = gridRenderer.patch_getContinuationActions().iterator();
        return actions.hasNext() ? actions.next() : null;
    }

    private static void addLibraryPlaylistRow(
            SharedBrowseRow libraryRow, PlaylistFolderLoad load) {
        String playlistBrowseId = libraryRow.patch_getPlaylistBrowseId();
        if (playlistBrowseId == null) return;
        // Episodes for Later (VLSE) has no Play button.
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(playlistBrowseId)) return;

        CharSequence titleText = libraryRow.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        // Subtitle and artwork are not needed for playback.
        LibraryPlaylistRow row = new LibraryPlaylistRow(
                playlistBrowseId,
                title,
                subtitleOrEmpty(libraryRow),
                artworkUriOrNull(libraryRow));
        synchronized (load) {
            if (load.androidAutoResultDelivered || !load.seenPlaylistBrowseIds.add(playlistBrowseId))
                return;
            load.libraryPlaylistRows.add(row);
        }
    }

    private static String subtitleOrEmpty(SharedBrowseRow libraryRow) {
        try {
            CharSequence subtitle = libraryRow.patch_getSubtitle();
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Uri artworkUriOrNull(SharedBrowseRow libraryRow) {
        try {
            return libraryRow.patch_getArtworkUri();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void deliverAndroidAutoPlaylists(
            AndroidAutoPlaylistsRequest androidAutoRequest,
            PlaylistFolderLoad load, String completionReason) {
        List<LibraryPlaylistRow> collectedRows;
        synchronized (load) {
            // Prevent the loader from changing the list while it is copied, and send only one result.
            if (load.androidAutoResultDelivered) return;
            load.androidAutoResultDelivered = true;
            collectedRows = new ArrayList<>(load.libraryPlaylistRows);
        }
        List<MediaBrowserCompat.MediaItem> androidAutoPlaylistItems =
                new ArrayList<>(collectedRows.size());
        for (LibraryPlaylistRow row : collectedRows) {
            try {
                androidAutoPlaylistItems.add(createDeferredPlaylistItem(row));
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not build a Library playlist item", ex);
            }
        }
        Logger.printDebug(() -> "YTM Library " + completionReason + "; returning " +
                androidAutoPlaylistItems.size() + " playlists");
        try {
            androidAutoRequest.patch_deliverAndroidAutoPlaylists(androidAutoPlaylistItems);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
        }
    }

    private static MediaBrowserCompat.MediaItem createDeferredPlaylistItem(
            LibraryPlaylistRow libraryRow) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                DEFERRED_PLAYLIST_MEDIA_ID_PREFIX + libraryRow.playlistBrowseId,
                libraryRow.title, libraryRow.subtitle, null, null, libraryRow.artworkUri,
                null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    /**
     * Injection point. Resolve this patch's deferred media ID, then ask YTM to start playback.
     *
     * @return true for this patch's media IDs, including failed or pending requests;
     *         false for YTM's own IDs. YTM cannot decode this patch's IDs.
     */
    public static boolean handlePlayFromMediaId(
            @NonNull MediaSession.Callback callback, @Nullable String mediaId,
            @Nullable Bundle extras) {
        if (mediaId == null || !mediaId.startsWith(DEFERRED_PLAYLIST_MEDIA_ID_PREFIX)) {
            PLAY_REQUEST_GENERATION.incrementAndGet();
            return false;
        }
        long requestGeneration = PLAY_REQUEST_GENERATION.incrementAndGet();
        PlaybackCallback playbackCallback = (PlaybackCallback) callback;
        String playlistBrowseId = mediaId.substring(DEFERRED_PLAYLIST_MEDIA_ID_PREFIX.length());
        PhoneBrowseRequests requests = phoneBrowseRequests;
        if (playlistBrowseId.isEmpty() || requests == null) {
            Logger.printDebug(() -> "Could not resolve selected Android Auto playlist");
            return true;
        }
        Handler callbackHandler = playbackCallback.patch_getCallbackHandler();
        if (callbackHandler == null) {
            Logger.printDebug(() -> "Android Auto playback callback is no longer active");
            return true;
        }
        Bundle playbackExtras = extras == null ? null : new Bundle(extras);
        // Ignore responses after the timeout. Once response processing starts, allow it to finish.
        AtomicBoolean completed = new AtomicBoolean();
        Utils.runOnMainThreadDelayed(() -> {
            if (completed.compareAndSet(false, true) &&
                    requestGeneration == PLAY_REQUEST_GENERATION.get()) {
                Logger.printDebug(() -> "Selected Android Auto playlist request timed out");
            }
        }, SELECTED_PLAYLIST_LOAD_TIMEOUT_MILLISECONDS);
        try {
            ListenableFuture<BrowseResponse> future =
                    requests.patch_requestBrowse(playlistBrowseId, BACKGROUND_EXECUTOR);
            future.addListener(() -> {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    BrowseResponse response = future.get();
                    // The playlist's Play button can be present even when there are no songs.
                    // Check for a song before asking YTM to play the playlist.
                    SharedBrowseRow firstPlayableSong = findFirstPlayableSong(response);
                    if (firstPlayableSong == null) {
                        Logger.printDebug(() ->
                                "Selected Android Auto playlist has no playable songs");
                        showNoPlayableSongsNotice(
                                playbackCallback, callbackHandler, requestGeneration);
                        return;
                    }
                    // Liked Music has no Play button; start it with the first playable song.
                    String nativePlayMediaId = LIKED_MUSIC_BROWSE_ID.equals(playlistBrowseId)
                            ? firstPlayableSong.patch_getActionMediaId()
                            : response.patch_getHeaderPlayMediaId();
                    if (nativePlayMediaId == null) {
                        Logger.printDebug(() -> "Selected Android Auto playlist has no Play action");
                        return;
                    }
                    // The response arrives on a background thread. Ask YTM to start playback
                    // on the thread it uses to handle playback commands.
                    callbackHandler.post(() -> {
                        // Do not start this playlist if the user has since selected something else,
                        // pressed Pause/Stop, or the service has replaced its Browse client.
                        // Check just before starting: any of these can happen while this task waits.
                        if (requestGeneration != PLAY_REQUEST_GENERATION.get() ||
                                requests != phoneBrowseRequests)
                            return;
                        callback.onPlayFromMediaId(nativePlayMediaId, playbackExtras);
                    });
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    Logger.printException(() -> "YTM playlist request interrupted: " +
                            playlistBrowseId, ex);
                } catch (ExecutionException | RuntimeException ex) {
                    Logger.printException(() -> "YTM playlist request failed: " +
                            playlistBrowseId, ex);
                }
            }, BACKGROUND_EXECUTOR);
        } catch (RuntimeException ex) {
            completed.set(true);
            Logger.printException(() -> "Could not request YTM playlist: " +
                    playlistBrowseId, ex);
        }
        return true;
    }

    /** Injection point. Prevent a pending playlist selection from starting after Pause/Stop. */
    public static void cancelPendingPlaylistPlayback() {
        // YTM cannot cancel a playback command it has not received yet.
        // Also suppress the empty-playlist message if the pending response contains no songs.
        PLAY_REQUEST_GENERATION.incrementAndGet();
    }

    private static SharedBrowseRow findFirstPlayableSong(BrowseResponse playlistResponse) {
        for (BrowseTab tab : playlistResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof OpenedPlaylistRows)) continue;
                for (SharedBrowseRow row :
                        ((OpenedPlaylistRows) sectionContent).patch_getRawRows()) {
                    // Exclude editor buttons such as "Add a song" from the song check.
                    if (!row.patch_hasPlayableVideoId()) continue;
                    if (row.patch_getActionMediaId() != null) return row;
                }
            }
        }
        return null;
    }

    /**
     * Injection point. Save the session that can update playback state for this callback.
     */
    public static void registerPlaybackSession(@NonNull PlaybackStateSession session) {
        synchronized (PLAYBACK_SESSIONS) {
            PLAYBACK_SESSIONS.put(
                    session.patch_getPlaybackOwner(), new WeakReference<>(session));
        }
    }

    /**
     * Injection point. Find the callback's session so it can show the empty-playlist message.
     */
    @Nullable
    public static PlaybackStateSession resolvePlaybackSession(@Nullable Object owner) {
        if (owner instanceof PlaybackStateSession) return (PlaybackStateSession) owner;
        if (owner == null) return null;
        synchronized (PLAYBACK_SESSIONS) {
            WeakReference<PlaybackStateSession> session = PLAYBACK_SESSIONS.get(owner);
            return session == null ? null : session.get();
        }
    }

    private static void showNoPlayableSongsNotice(
            PlaybackCallback callback, Handler callbackHandler, long requestGeneration) {
        callbackHandler.post(() -> {
            if (requestGeneration != PLAY_REQUEST_GENERATION.get()) return;
            try {
                PlaybackStateSession session = callback.patch_getPlaybackStateSession();
                if (session == null) return;
                PlaybackStateCompat currentPlaybackState = session.patch_getPlaybackState();
                if (currentPlaybackState == null) {
                    // TODO: Find a way to show the empty-playlist message without an existing playback state.
                    Logger.printDebug(() -> "No playback state for no-tracks notice");
                    return;
                }
                // Do not replace an error YTM is already reporting.
                if (currentPlaybackState.f != 0) return;
                CharSequence message = ResourceUtils.getString(NO_TRACKS_MESSAGE_RESOURCE_NAME);
                // Android Auto needs an error code in PlaybackStateCompat to display the message.
                // Keep the playback status unchanged so the current song is not marked as failed.
                PlaybackStateCompat noPlayableSongsNotice = new PlaybackStateCompat(
                        currentPlaybackState.a, currentPlaybackState.b, currentPlaybackState.c,
                        currentPlaybackState.d, currentPlaybackState.e, APP_ERROR_CODE, message,
                        currentPlaybackState.h, currentPlaybackState.i, currentPlaybackState.j,
                        currentPlaybackState.k);
                session.patch_setPlaybackState(noPlayableSongsNotice);
                callbackHandler.postDelayed(() -> {
                    try {
                        // Do not restore an old state if playback changed while the message was visible.
                        if (session.patch_getPlaybackState() == noPlayableSongsNotice)
                            session.patch_setPlaybackState(currentPlaybackState);
                    } catch (RuntimeException ex) {
                        Logger.printException(() -> "Could not clear no-tracks notice", ex);
                    }
                }, NO_TRACKS_NOTICE_MILLISECONDS);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not show no-tracks notice", ex);
            }
        });
    }

    private static final class PlaylistFolderLoad {
        private final PhoneBrowseRequests phoneBrowseRequests;
        private final List<LibraryPlaylistRow> libraryPlaylistRows = new ArrayList<>();
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        private boolean androidAutoResultDelivered;

        private PlaylistFolderLoad(PhoneBrowseRequests requests) {
            this.phoneBrowseRequests = requests;
        }
    }

    private static final class LibraryPlaylistRow {
        private final String playlistBrowseId;
        private final String title;
        private final String subtitle;
        private final Uri artworkUri;

        private LibraryPlaylistRow(
                String playlistBrowseId, String title, String subtitle, Uri artworkUri) {
            this.playlistBrowseId = playlistBrowseId;
            this.title = title;
            this.subtitle = subtitle;
            this.artworkUri = artworkUri;
        }
    }
}
