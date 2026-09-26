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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 * Adds phone Library playlists and Home podcast folders to Android Auto.
 * Load playlist contents only when selected to avoid delaying the Playlists folder.
 * YTM handles playback and podcast browsing.
 */
@SuppressWarnings("unused")
public final class SupportAndroidAutoPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String DEFERRED_PLAYLIST_MEDIA_ID_PREFIX = "morphe:aa:playlist:";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    // Limit the total time spent loading Library pages before returning the collected playlists.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 30_000;
    private static final int SELECTED_PLAYLIST_LOAD_TIMEOUT_MILLISECONDS = 30_000;
    // Allow phone Library time to return updated artwork after a successful edit.
    private static final int PLAYLIST_EDIT_REFRESH_DELAY_MILLISECONDS = 5_000;
    private static final int NO_TRACKS_NOTICE_MILLISECONDS = 8_000;
    private static final int APP_ERROR_CODE = 1;
    private static final String NO_TRACKS_MESSAGE_RESOURCE_NAME = "sideloaded_playlists_no_tracks";
    private static final String ANDROID_AUTO_ROOT_MEDIA_ID = "com.google.android.projection.gearhead";
    private static final String PODCASTS_MEDIA_ID = "morphe:aa:podcasts";
    private static final String PODCASTS_TITLE_RESOURCE_NAME = "offline_podcasts_shelf_title";
    private static final String SINGLE_ITEM_HINT = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT";
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    // A user playlist can also be named Playlists. The title match only affects opening
    // folders; it does not change playlist playback.
    private static final Set<String> PLAYLISTS_TITLE_MATCH_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();
    // Reject pending playback after another selection or Pause/Stop.
    private static final AtomicLong PLAY_REQUEST_GENERATION = new AtomicLong();
    private static final AtomicLong PLAYLIST_LOAD_GENERATION = new AtomicLong();
    private static final WeakHashMap<Object, Map<String, AtomicLong>>
            PLAYLIST_FOLDER_DELIVERIES = new WeakHashMap<>();
    private static final WeakHashMap<Object, WeakReference<PlaybackStateSession>>
            PLAYBACK_SESSIONS = new WeakHashMap<>();
    @Nullable
    private static volatile PlaylistSubscription playlistSubscription;
    private static String androidAutoHomeMediaId;
    private static List<MediaBrowserCompat.MediaItem> androidAutoPodcastFolders =
            Collections.emptyList();

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
        // Connection receiving the folder result; null for other browser implementations.
        @Nullable Object patch_getBrowserConnection();
        void patch_deliverAndroidAutoPlaylists(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoPlaylists);
    }

    // Reloads the Playlists folder through its existing Android Auto connection.
    public interface AndroidAutoPlaylistReload {
        void patch_reloadPlaylistFolder(@NonNull String parentMediaId, @NonNull Object connection);
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

    @Nullable
    private static volatile PhoneBrowseRequests phoneBrowseRequests;

    private SupportAndroidAutoPatch() {
    }

    /**
     * Injection point. Save the client created by MusicBrowserService for Library and playlist requests.
     */
    public static void setPhoneBrowseRequests(@NonNull PhoneBrowseRequests requests) {
        phoneBrowseRequests = requests;
        playlistSubscription = null;
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
     * Injection point. Save the Playlists connection so phone edits can refresh the open folder.
     */
    public static void rememberPlaylistsSubscription(
            @NonNull AndroidAutoPlaylistReload browser,
            @Nullable String parentMediaId,
            @NonNull Object connection) {
        if (parentMediaId == null || !PLAYLISTS_TITLE_MATCH_MEDIA_IDS.contains(parentMediaId))
            return;
        playlistSubscription = new PlaylistSubscription(browser, parentMediaId, connection);
    }

    /**
     * Injection point. Refresh Android Auto Playlists after a successful playlist edit on the phone.
     */
    public static void watchPlaylistEdit(@Nullable ListenableFuture<?> editResult) {
        if (editResult == null || playlistSubscription == null) return;
        try {
            editResult.addListener(() -> {
                try {
                    editResult.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | RuntimeException ex) {
                    Logger.printException(() -> "Playlist edit failed", ex);
                    return;
                }
                Utils.runOnMainThreadDelayed(() -> {
                    PlaylistSubscription subscription = playlistSubscription;
                    if (subscription == null) return;
                    AndroidAutoPlaylistReload browser = subscription.browser.get();
                    Object connection = subscription.connection.get();
                    if (browser == null || connection == null) return;
                    try {
                        browser.patch_reloadPlaylistFolder(subscription.parentMediaId, connection);
                    } catch (RuntimeException ex) {
                        Logger.printException(() -> "Could not refresh Android Auto Playlists", ex);
                    }
                }, PLAYLIST_EDIT_REFRESH_DELAY_MILLISECONDS);
            }, BACKGROUND_EXECUTOR);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not observe playlist edit", ex);
        }
    }

    /**
     * Injection point. Load the Playlists folder asynchronously. YTM has already called
     * MediaBrowserService.Result.detach(), allowing the result to be sent after this method returns.
     *
     * <p>On failure or timeout, return the playlists collected so far, or an empty list if none.
     *
     * @return true if this method handles the folder result; false to let YTM handle the request.
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
            Object connection = androidAutoRequest.patch_getBrowserConnection();
            AtomicLong deliveredGeneration;
            synchronized (PLAYLIST_FOLDER_DELIVERIES) {
                deliveredGeneration = connection == null ? new AtomicLong() :
                        PLAYLIST_FOLDER_DELIVERIES
                                .computeIfAbsent(connection, ignored -> new HashMap<>())
                                .computeIfAbsent(requestedMediaId, ignored -> new AtomicLong());
            }
            PlaylistFolderLoad load = new PlaylistFolderLoad(phoneRequests, deliveredGeneration);
            try {
                Utils.runOnMainThreadDelayed(
                        () -> deliverAndroidAutoPlaylists(androidAutoRequest, load, "timed out"),
                        ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS);
                requestPhoneLibrary(androidAutoRequest, load);
            } catch (RuntimeException ex) {
                // Returning false would let YTM answer a request our scheduled timeout can still answer.
                Logger.printException(() -> "Could not request YTM Library", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto Playlists request", ex);
            return false;
        }
    }

    /**
     * Injection point. Add a Podcasts tab and reuse YTM's native Home podcast folders.
     */
    @Nullable
    public static synchronized List<MediaBrowserCompat.MediaItem> restoreAndroidAutoPodcastItems(
            @NonNull AndroidAutoPlaylistsRequest request,
            @Nullable List<MediaBrowserCompat.MediaItem> items) {
        try {
            String parentMediaId = request.patch_getRequestedMediaId();
            if (ANDROID_AUTO_ROOT_MEDIA_ID.equals(parentMediaId)) {
                // Discard folders and connections from a previous account or Android Auto connection.
                playlistSubscription = null;
                androidAutoHomeMediaId = null;
                androidAutoPodcastFolders = Collections.emptyList();
                // The two-item root is Home, Library. YTM's own Podcasts tab adds a third item.
                if (items == null || items.size() != 2) return items;

                androidAutoHomeMediaId = items.get(0).a();
                List<MediaBrowserCompat.MediaItem> rootItems = new ArrayList<>(items);
                MediaDescriptionCompat podcastsDescription = new MediaDescriptionCompat(
                        PODCASTS_MEDIA_ID,
                        ResourceUtils.getString(PODCASTS_TITLE_RESOURCE_NAME),
                        null, null, null, null, null, null);
                rootItems.add(1, new MediaBrowserCompat.MediaItem(
                        podcastsDescription, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
                return rootItems;
            }
            if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
                return new ArrayList<>(androidAutoPodcastFolders);
            }
            if (parentMediaId != null && parentMediaId.equals(androidAutoHomeMediaId) &&
                    items != null) {
                // TODO: Investigate why Android Auto Speed dial returns only podcasts.
                List<MediaBrowserCompat.MediaItem> folders = new ArrayList<>();
                for (MediaBrowserCompat.MediaItem item : items) {
                    Bundle extras = item.a.f;
                    // Home's podcast folders share this layout hint. Speed dial can carry it too.
                    if (item.b() && extras != null && extras.containsKey(SINGLE_ITEM_HINT)) {
                        folders.add(item);
                    }
                }
                androidAutoPodcastFolders = folders;
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not restore Android Auto Podcasts", ex);
        }
        return items;
    }

    private static void requestPhoneLibrary(
            AndroidAutoPlaylistsRequest androidAutoRequest, PlaylistFolderLoad load) {
        handleLibraryResponse(
                androidAutoRequest, load,
                load.phoneBrowseRequests.patch_requestBrowse(
                        PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR),
                SupportAndroidAutoPatch::collectInitialLibraryPlaylists);
    }

    private static void requestLibraryContinuation(
            AndroidAutoPlaylistsRequest androidAutoRequest, PlaylistFolderLoad load,
            Object continuationAction) {
        handleLibraryResponse(
                androidAutoRequest, load,
                load.phoneBrowseRequests.patch_requestLibraryContinuation(
                        continuationAction, BACKGROUND_EXECUTOR),
                SupportAndroidAutoPatch::collectPaginatedLibraryPlaylists);
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
        // Library pages supply at most one action for loading the next page.
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
        // Keep the playlist available if its subtitle or artwork cannot be read.
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
            // The timeout and page loader can both finish this request; send only one result.
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
        synchronized (load.deliveredGeneration) {
            // An older load must not replace a newer result for the same folder and connection.
            if (load.requestGeneration < load.deliveredGeneration.get()) return;
            Logger.printDebug(() -> "YTM Library " + completionReason + "; returning " +
                    androidAutoPlaylistItems.size() + " playlists");
            try {
                androidAutoRequest.patch_deliverAndroidAutoPlaylists(androidAutoPlaylistItems);
                load.deliveredGeneration.set(load.requestGeneration);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
            }
        }
    }

    private static MediaBrowserCompat.MediaItem createDeferredPlaylistItem(
            LibraryPlaylistRow libraryRow) {
        // Store the Browse ID (VL...) here; resolve YTM's playback command only when selected.
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
     * <p>Use YTM's empty-playlist message when no playable songs are found. Log request errors,
     * timeouts, and missing Play commands without changing playback.
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
                    // An empty playlist can still have a Play button.
                    SharedBrowseRow firstPlayableSong = findFirstPlayableSong(response);
                    if (firstPlayableSong == null) {
                        Logger.printDebug(() ->
                                "Selected Android Auto playlist has no playable songs");
                        showNoPlayableSongsNotice(
                                playbackCallback, callbackHandler, requestGeneration, requests);
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
                        // Recheck for another selection, Pause/Stop, or a replaced Browse client
                        // after waiting for YTM's playback thread.
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

    /** Injection point. Cancel pending playlist playback and empty-playlist messages on Pause/Stop. */
    public static void cancelPendingPlaylistPlayback() {
        // YTM cannot cancel a playback command it has not received yet.
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
     * Injection point. Save the callback's session for the empty-playlist message.
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
            PlaybackCallback callback, Handler callbackHandler, long requestGeneration,
            PhoneBrowseRequests requests) {
        callbackHandler.post(() -> {
            if (requestGeneration != PLAY_REQUEST_GENERATION.get() ||
                    requests != phoneBrowseRequests)
                return;
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

    private static final class PlaylistSubscription {
        private final WeakReference<AndroidAutoPlaylistReload> browser;
        private final String parentMediaId;
        private final WeakReference<Object> connection;

        private PlaylistSubscription(
                AndroidAutoPlaylistReload browser, String parentMediaId, Object connection) {
            this.browser = new WeakReference<>(browser);
            this.parentMediaId = parentMediaId;
            this.connection = new WeakReference<>(connection);
        }
    }

    private static final class PlaylistFolderLoad {
        // Keep an in-progress load on its original client if MusicBrowserService is recreated.
        private final PhoneBrowseRequests phoneBrowseRequests;
        private final long requestGeneration = PLAYLIST_LOAD_GENERATION.incrementAndGet();
        private final AtomicLong deliveredGeneration;
        private final List<LibraryPlaylistRow> libraryPlaylistRows = new ArrayList<>();
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        private boolean androidAutoResultDelivered;

        private PlaylistFolderLoad(PhoneBrowseRequests requests, AtomicLong deliveredGeneration) {
            this.phoneBrowseRequests = requests;
            this.deliveredGeneration = deliveredGeneration;
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
