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
import android.os.Looper;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Adds YT Music support in Android Auto by intercepting requests for the Playlists folder,
 * loading playlists from the phone Library, and adding a Podcasts tab.
 * Open the Playlists folder in Android Auto's Library to see the list of playlists.
 *
 * Intercept Android Auto requests: {@link #handleAndroidAutoPlaylists}.
 * Request and load playlists: {@link #requestLibraryPage}, {@link #collectPlaylistsFromGrid}.
 * Pagination: {@link #requestLibraryPage}, {@link #appendPaginatedLibraryPlaylists}, {@link #firstPaginationCommand}.
 * Return playlists to Android Auto: {@link #deliverAndroidAutoPlaylists}.
 * Playlist titles and artwork: {@link #addLibraryPlaylist}, {@link #artworkUriOrNull}.
 * Play a selected playlist: {@link #handlePlayFromMediaId}, {@link PlaylistPlaybackRequest}.
 * Check playlist contents: {@link #findFirstPlayableSong}, {@link #showNoPlayableSongsNotice}.
 * Podcasts: {@link #handleAndroidAutoBrowseResult}.
 * Refresh after Library changes: {@link #watchLibraryChange}.
 */
@SuppressWarnings("unused")
public final class SupportAndroidAutoPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String DEFERRED_PLAYLIST_MEDIA_ID_PREFIX = "morphe:aa:playlist:";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    // Return collected playlists when this timeout expires.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 30_000;
    private static final int SELECTED_PLAYLIST_LOAD_TIMEOUT_MILLISECONDS = 30_000;
    private static final int LIBRARY_REFRESH_DELAY_MILLISECONDS = 5_000;
    private static final int NO_TRACKS_NOTICE_MILLISECONDS = 8_000;
    private static final int EMPTY_PLAYLIST_ERROR_CODE = 1;
    private static final String NO_TRACKS_MESSAGE_RESOURCE_NAME = "sideloaded_playlists_no_tracks";
    private static final String ANDROID_AUTO_ROOT_MEDIA_ID = "com.google.android.projection.gearhead";
    private static final String PODCASTS_MEDIA_ID = "morphe:aa:podcasts";
    private static final String PODCASTS_TITLE_RESOURCE_NAME = "offline_podcasts_shelf_title";
    private static final String UPGRADE_PROMPT_MEDIA_ID = "promotion_version_1";
    private static final String FORCE_REFRESH = "com.google.android.apps.youtube.music.mediabrowser.force_refresh";
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    private static final Handler REFRESH_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH_LIBRARY = SupportAndroidAutoPatch::refreshAndroidAutoLibrary;
    // A user's playlist can also be named "Playlists"; do not use these title matches for playback.
    private static final Set<String> PLAYLISTS_TITLE_MATCH_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();
    // Selecting music or pressing Pause/Stop changes this number; ignore pending requests with older numbers.
    private static final AtomicLong PLAY_REQUEST_GENERATION = new AtomicLong();
    // Give each Library load a number to distinguish earlier requests from later requests.
    private static final AtomicLong PLAYLISTS_LOAD_COUNTER = new AtomicLong();
    // Remember which Library load last updated each folder on each Android Auto connection.
    private static final WeakHashMap<Object, Map<String, AtomicLong>>
            PLAYLISTS_FOLDER_DELIVERIES = new WeakHashMap<>();
    private static final WeakHashMap<Object, WeakReference<PlaybackStateSession>>
            PLAYBACK_SESSIONS = new WeakHashMap<>();
    // Android Auto's saved request to receive updates for the Playlists folder.
    @Nullable
    private static AndroidAutoSubscription playlistsSubscription;
    @Nullable
    private static AndroidAutoSubscription podcastsSubscription;
    @Nullable
    private static AndroidAutoSubscription homeSubscription;
    private static String androidAutoHomeMediaId;
    private static List<MediaBrowserCompat.MediaItem> cachedAndroidAutoPodcastFolders =
            Collections.emptyList();

    // Kotlin adds the methods in these interfaces to YTM classes for this Java code to call.

    // YTM's methods for requesting phone pages by Browse ID, such as the Library or a playlist.
    public interface PhoneBrowseRequests {
        @NonNull ListenableFuture<PhoneBrowseResponse> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<PhoneBrowseResponse> patch_requestLibraryPagination(
                @NonNull Object paginationCommand, @NonNull Executor executor);
    }

    // Library or playlist data returned by YTM's phone requests.
    public interface PhoneBrowseResponse {
        // Tab data containing Library items or playlist songs.
        @NonNull Iterable<PhoneBrowseTab> patch_getTabs();
        // More Library items returned by pagination.
        @Nullable GridRenderer patch_getPaginatedLibraryGrid();
        // Command from the Play button above the playlist's songs, encoded as an Android Auto media ID.
        @Nullable String patch_getPlaylistPlayButtonMediaId();
    }

    // YTM's TabRenderer groups a phone page into sections, even when that page has no visible tabs.
    public interface PhoneBrowseTab {
        @Nullable SectionList patch_getSectionList();
    }

    // Sections can contain a Library grid (GridRenderer) or playlist contents (PlaylistContents).
    public interface SectionList {
        @NonNull Iterable<?> patch_getContents();
    }

    // YTM's phone Library grid: items to display and pagination commands.
    public interface GridRenderer {
        // Includes artists and podcasts as well as playlists; filter before returning playlists to Android Auto.
        @NonNull Iterable<?> patch_getItems();
        // YTM's pagination commands: NEXT requests the next Library page; RELOAD refreshes the list.
        @NonNull Iterable<?> patch_getPaginationCommands();
    }

    // Playlist contents include songs and the "Add a song" button.
    public interface PlaylistContents {
        @NonNull Iterable<PhoneBrowseItem> patch_getItems();
    }

    // Android Auto requests media items for its root tabs, a tab's contents, or a folder's contents.
    public interface AndroidAutoBrowseRequest {
        @Nullable String patch_getRequestedMediaId();
        // The Android Auto connection for this request, or null if unknown.
        @Nullable Object patch_getBrowserConnection();
        void patch_deliverAndroidAutoItems(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoItems);
    }

    // Refreshes an Android Auto folder without reconnecting.
    public interface AndroidAutoFolderReload {
        void patch_reloadFolder(
                @NonNull String parentMediaId, @NonNull Object connection, @Nullable Bundle options);
    }

    public interface PlaybackCallback {
        @Nullable Handler patch_getCallbackHandler();
        @Nullable PlaybackStateSession patch_getPlaybackStateSession();
    }

    // YTM's media session: playback status and the message displayed by Android Auto.
    public interface PlaybackStateSession {
        @NonNull Object patch_getPlaybackOwner();
        @Nullable PlaybackStateCompat patch_getPlaybackState();
        // YTM's setter also updates Android Auto.
        void patch_setPlaybackState(@NonNull PlaybackStateCompat state);
    }

    // YTM uses this item type for Library content, playlist songs, and the "Add a song" button.
    public interface PhoneBrowseItem {
        @Nullable String patch_getPlaylistBrowseId();
        @Nullable String patch_getCommandMediaId();
        // YTM calls a song's identifier a video ID, even when only audio is played.
        boolean patch_hasPlayableVideoId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    @Nullable
    private static volatile PhoneBrowseRequests phoneBrowseRequests;

    private SupportAndroidAutoPatch() {
    }

    // Capture YTM's Library request methods and identify the Playlists folder

    /**
     * Injection point. Save the object MusicBrowserService uses to request Library and playlist pages.
     */
    public static synchronized void setPhoneBrowseRequests(@NonNull PhoneBrowseRequests requests) {
        phoneBrowseRequests = requests;
        playlistsSubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        REFRESH_HANDLER.removeCallbacks(REFRESH_LIBRARY);
        Logger.printDebug(() -> "Ready to request phone Library and playlist contents: " +
                requests.getClass().getName());
    }

    /**
     * Injection point. Identify the Playlists folder by its translated title; its ID varies.
     */
    public static void rememberPlaylistsTitleMatch(
            @Nullable String androidAutoMediaId, @Nullable CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE_NAME)
                .contentEquals(title)) return;
        if (androidAutoMediaId != null) PLAYLISTS_TITLE_MATCH_MEDIA_IDS.add(androidAutoMediaId);
    }

    // Intercept Android Auto requests for the Playlists folder

    /**
     * Injection point. Load playlists when Android Auto opens the Playlists folder.
     * YTM calls detach() before this hook, allowing the list to be returned asynchronously.
     *
     * <p>On failure or timeout, return the playlists collected so far, or an empty list if none.
     *
     * @return true once this patch accepts the request, even while loading;
     *         false to let YTM handle the request.
     */
    public static boolean handleAndroidAutoPlaylists(
            @NonNull AndroidAutoBrowseRequest androidAutoRequest) {
        try {
            PhoneBrowseRequests phoneRequests = phoneBrowseRequests;
            if (phoneRequests == null) return false;
            String requestedMediaId = androidAutoRequest.patch_getRequestedMediaId();
            if (requestedMediaId == null) return false;
            if (!PLAYLISTS_TITLE_MATCH_MEDIA_IDS.contains(requestedMediaId)) return false;
            Object connection = androidAutoRequest.patch_getBrowserConnection();
            AtomicLong lastDeliveredLoadNumber;
            synchronized (PLAYLISTS_FOLDER_DELIVERIES) {
                if (connection == null) {
                    // Without the connection, there is no way to identify other requests for this same folder.
                    lastDeliveredLoadNumber = new AtomicLong();
                } else {
                    // Reopening Playlists or editing a playlist on the phone can start another load
                    // before this one finishes.
                    lastDeliveredLoadNumber = PLAYLISTS_FOLDER_DELIVERIES
                            .computeIfAbsent(connection, ignored -> new HashMap<>())
                            .computeIfAbsent(requestedMediaId, ignored -> new AtomicLong());
                }
            }
            PlaylistsFolderLoad load = new PlaylistsFolderLoad(phoneRequests, lastDeliveredLoadNumber);
            try {
                Utils.runOnMainThreadDelayed(
                        () -> deliverAndroidAutoPlaylists(androidAutoRequest, load, "timed out"),
                        ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS);
                requestLibraryPage(androidAutoRequest, load, null);
            } catch (RuntimeException ex) {
                // Only this patch should answer the request, including after failure.
                Logger.printException(() -> "Could not request YTM Library", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto Playlists request", ex);
            return false;
        }
    }

    // Playlist loading and pagination

    // Pagination loads the Library one page at a time; each page is the items returned by one request.
    // Stop when there are no more pages, a request fails, or the load times out.
    private static void requestLibraryPage(
            AndroidAutoBrowseRequest androidAutoRequest, PlaylistsFolderLoad load,
            @Nullable Object paginationCommand) {
        boolean firstPage = paginationCommand == null;
        ListenableFuture<PhoneBrowseResponse> libraryResponseFuture = firstPage
                ? load.phoneBrowseRequests.patch_requestBrowse(
                        PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR)
                : load.phoneBrowseRequests.patch_requestLibraryPagination(
                        paginationCommand, BACKGROUND_EXECUTOR);
        libraryResponseFuture.addListener(() -> {
            synchronized (load) {
                if (load.androidAutoDeliveryStarted) return;
            }
            try {
                PhoneBrowseResponse libraryResponse = libraryResponseFuture.get();
                Object nextPaginationCommand = firstPage
                        ? appendInitialLibraryPlaylists(libraryResponse, load)
                        : appendPaginatedLibraryPlaylists(libraryResponse, load);
                synchronized (load) {
                    // The timeout may have started returning playlists while this Library response was being read.
                    if (load.androidAutoDeliveryStarted) return;
                }
                if (nextPaginationCommand != null) {
                    requestLibraryPage(androidAutoRequest, load, nextPaginationCommand);
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

    private static Object appendInitialLibraryPlaylists(
            PhoneBrowseResponse libraryResponse, PlaylistsFolderLoad load) {
        Object paginationCommand = null;
        for (PhoneBrowseTab tab : libraryResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof GridRenderer)) continue;
                GridRenderer gridRenderer = (GridRenderer) sectionContent;
                collectPlaylistsFromGrid(gridRenderer, load);
                if (paginationCommand == null) {
                    paginationCommand = firstPaginationCommand(gridRenderer);
                }
            }
        }
        synchronized (load) {
            Logger.printDebug(() -> "Found playlists in phone Library: " +
                    load.libraryPlaylists.size());
        }
        return paginationCommand;
    }

    private static Object appendPaginatedLibraryPlaylists(
            PhoneBrowseResponse libraryResponse, PlaylistsFolderLoad load) {
        GridRenderer gridRenderer = libraryResponse.patch_getPaginatedLibraryGrid();
        if (gridRenderer == null) return null;
        collectPlaylistsFromGrid(gridRenderer, load);
        synchronized (load) {
            Logger.printDebug(() -> "Found playlists in phone Library: " +
                    load.libraryPlaylists.size());
        }
        return firstPaginationCommand(gridRenderer);
    }

    private static void collectPlaylistsFromGrid(
            GridRenderer gridRenderer, PlaylistsFolderLoad load) {
        for (Object libraryItem : gridRenderer.patch_getItems()) {
            if (!(libraryItem instanceof PhoneBrowseItem)) continue;
            try {
                addLibraryPlaylist((PhoneBrowseItem) libraryItem, load);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not read a phone Library item", ex);
            }
        }
    }

    @Nullable
    private static Object firstPaginationCommand(GridRenderer gridRenderer) {
        // TODO: Check whether pagination needs every command when YTM returns more than one.
        Iterator<?> commands = gridRenderer.patch_getPaginationCommands().iterator();
        return commands.hasNext() ? commands.next() : null;
    }

    // Playlist titles and artwork

    private static void addLibraryPlaylist(
            PhoneBrowseItem libraryItem, PlaylistsFolderLoad load) {
        String playlistBrowseId = libraryItem.patch_getPlaylistBrowseId();
        if (playlistBrowseId == null) return;
        // Episodes for Later (VLSE) has no Play button.
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(playlistBrowseId)) return;

        CharSequence titleText = libraryItem.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        // Keep the playlist available if its subtitle or artwork cannot be read.
        LibraryPlaylist playlist = new LibraryPlaylist(
                playlistBrowseId,
                title,
                subtitleOrEmpty(libraryItem),
                artworkUriOrNull(libraryItem));
        // Read titles and artwork before locking so a slow read cannot block timeout handling.
        synchronized (load) {
            if (load.androidAutoDeliveryStarted || !load.seenPlaylistBrowseIds.add(playlistBrowseId))
                return;
            load.libraryPlaylists.add(playlist);
        }
    }

    private static String subtitleOrEmpty(PhoneBrowseItem libraryItem) {
        try {
            CharSequence subtitle = libraryItem.patch_getSubtitle();
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Uri artworkUriOrNull(PhoneBrowseItem libraryItem) {
        try {
            return libraryItem.patch_getArtworkUri();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // Return playlists to Android Auto

    private static void deliverAndroidAutoPlaylists(
            AndroidAutoBrowseRequest androidAutoRequest,
            PlaylistsFolderLoad load, String logReason) {
        List<LibraryPlaylist> collectedPlaylists = load.takePlaylistsForDelivery();
        if (collectedPlaylists == null) return;

        List<MediaBrowserCompat.MediaItem> androidAutoPlaylistItems =
                new ArrayList<>(collectedPlaylists.size());
        for (LibraryPlaylist playlist : collectedPlaylists) {
            try {
                androidAutoPlaylistItems.add(createDeferredPlaylistItem(playlist));
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not build an Android Auto playlist item", ex);
            }
        }
        deliverUnlessNewerLoadAlreadyDelivered(androidAutoRequest, load, androidAutoPlaylistItems, logReason);
    }

    private static void deliverUnlessNewerLoadAlreadyDelivered(
            AndroidAutoBrowseRequest androidAutoRequest, PlaylistsFolderLoad load,
            List<MediaBrowserCompat.MediaItem> androidAutoPlaylistItems, String logReason) {
        synchronized (load.lastDeliveredLoadNumber) {
            // A newer request can still be loading; reject this list only if newer results have already been sent.
            if (load.loadNumber < load.lastDeliveredLoadNumber.get()) return;
            Logger.printDebug(() -> "YTM Library " + logReason + "; returning " +
                    androidAutoPlaylistItems.size() + " playlists");
            try {
                androidAutoRequest.patch_deliverAndroidAutoItems(androidAutoPlaylistItems);
                // Update only after sending succeeds, so a failed send does not block an older list.
                load.lastDeliveredLoadNumber.set(load.loadNumber);
            } catch (RuntimeException ex) {
                // Do not retry: YTM may already consider this request answered.
                // Reopening Playlists starts a new request.
                Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
            }
        }
    }

    private static MediaBrowserCompat.MediaItem createDeferredPlaylistItem(
            LibraryPlaylist playlist) {
        // Store the playlist's page ID (VL...) so its songs are requested only when the user selects it.
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                DEFERRED_PLAYLIST_MEDIA_ID_PREFIX + playlist.playlistBrowseId,
                playlist.title, playlist.subtitle, null, null, playlist.artworkUri,
                null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static final class PlaylistsFolderLoad {
        // Use the same YTM object throughout pagination, even if MusicBrowserService restarts.
        private final PhoneBrowseRequests phoneBrowseRequests;
        private final long loadNumber = PLAYLISTS_LOAD_COUNTER.incrementAndGet();
        private final AtomicLong lastDeliveredLoadNumber;
        private final List<LibraryPlaylist> libraryPlaylists = new ArrayList<>();
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        private boolean androidAutoDeliveryStarted;

        private PlaylistsFolderLoad(PhoneBrowseRequests requests, AtomicLong lastDeliveredLoadNumber) {
            this.phoneBrowseRequests = requests;
            this.lastDeliveredLoadNumber = lastDeliveredLoadNumber;
        }

        @Nullable
        private synchronized List<LibraryPlaylist> takePlaylistsForDelivery() {
            // The timeout and the Library loader must not both send a list for this request.
            // Null means another call already took the playlists; an empty list still needs to be sent.
            if (androidAutoDeliveryStarted) return null;
            androidAutoDeliveryStarted = true;
            return new ArrayList<>(libraryPlaylists);
        }
    }

    private static final class LibraryPlaylist {
        private final String playlistBrowseId;
        private final String title;
        private final String subtitle;
        private final Uri artworkUri;

        private LibraryPlaylist(
                String playlistBrowseId, String title, String subtitle, Uri artworkUri) {
            this.playlistBrowseId = playlistBrowseId;
            this.title = title;
            this.subtitle = subtitle;
            this.artworkUri = artworkUri;
        }
    }

    // Refresh after Library changes

    /**
     * Injection point. Save Android Auto's requests for Playlists, Home, and Podcasts for later refreshes.
     */
    public static synchronized void rememberAndroidAutoSubscription(
            @NonNull AndroidAutoFolderReload browserService,
            @Nullable String parentMediaId,
            @NonNull Object connection) {
        if (parentMediaId == null) return;
        if (PLAYLISTS_TITLE_MATCH_MEDIA_IDS.contains(parentMediaId)) {
            playlistsSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        } else if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
            podcastsSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        } else if (parentMediaId.equals(androidAutoHomeMediaId)) {
            homeSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        }
    }

    /**
     * Injection point. Refresh Android Auto after a successful playlist edit, Like, or saved-show change.
     */
    public static void watchLibraryChange(@Nullable ListenableFuture<?> changeResult) {
        if (changeResult == null) return;
        synchronized (SupportAndroidAutoPatch.class) {
            if (playlistsSubscription == null && homeSubscription == null) return;
        }
        try {
            changeResult.addListener(() -> {
                try {
                    // The request has finished; get() throws if it failed.
                    changeResult.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | RuntimeException ex) {
                    Logger.printException(() -> "Library change failed", ex);
                    return;
                }
                scheduleLibraryRefresh();
            }, BACKGROUND_EXECUTOR);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not observe Library change", ex);
        }
    }

    private static synchronized void scheduleLibraryRefresh() {
        // Wait for updated playlist artwork and combine edits made close together into one refresh.
        REFRESH_HANDLER.removeCallbacks(REFRESH_LIBRARY);
        REFRESH_HANDLER.postDelayed(REFRESH_LIBRARY, LIBRARY_REFRESH_DELAY_MILLISECONDS);
    }

    private static void refreshAndroidAutoLibrary() {
        AndroidAutoSubscription playlists;
        AndroidAutoSubscription home;
        // Use the current requests if Android Auto reconnected while the changes were pending.
        synchronized (SupportAndroidAutoPatch.class) {
            playlists = playlistsSubscription;
            home = homeSubscription;
        }
        // YTM holds its own lock while delivering results; do not hold ours when calling back into YTM.
        if (playlists != null) playlists.reload(false);
        // Cached Home results retain the old shows. Fetch Home again, then update Podcasts on receipt.
        if (home != null) home.reload(true);
    }

    // A subscription is Android Auto's request to receive updates for a folder.
    private static final class AndroidAutoSubscription {
        private final WeakReference<AndroidAutoFolderReload> browserService;
        private final String parentMediaId;
        private final WeakReference<Object> connection;

        private AndroidAutoSubscription(
                AndroidAutoFolderReload browserService, String parentMediaId, Object connection) {
            this.browserService = new WeakReference<>(browserService);
            this.parentMediaId = parentMediaId;
            this.connection = new WeakReference<>(connection);
        }

        private void reload(boolean forceRefresh) {
            AndroidAutoFolderReload service = browserService.get();
            Object connectedBrowser = connection.get();
            // Weak references allow the browser service and connection to be released after disconnection.
            if (service == null || connectedBrowser == null) return;
            try {
                Bundle options = null;
                if (forceRefresh) {
                    options = new Bundle();
                    options.putBoolean(FORCE_REFRESH, true);
                }
                service.patch_reloadFolder(parentMediaId, connectedBrowser, options);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not refresh Android Auto folder: " + parentMediaId, ex);
            }
        }
    }

    // Podcasts

    /**
     * Injection point. Add the Podcasts tab.
     */
    @Nullable
    public static synchronized List<MediaBrowserCompat.MediaItem> handleAndroidAutoBrowseResult(
            @NonNull AndroidAutoBrowseRequest request,
            @Nullable List<MediaBrowserCompat.MediaItem> ytmItems) {
        try {
            String parentMediaId = request.patch_getRequestedMediaId();
            if (ANDROID_AUTO_ROOT_MEDIA_ID.equals(parentMediaId)) {
                return initializeAndroidAutoTabs(ytmItems);
            }
            if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
                return new ArrayList<>(cachedAndroidAutoPodcastFolders);
            }
            if (parentMediaId != null && parentMediaId.equals(androidAutoHomeMediaId) &&
                    ytmItems != null) {
                cacheAndroidAutoPodcastFolders(ytmItems);
                refreshPodcastsAfterHomeLoad();
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto browse result", ex);
        }
        return ytmItems;
    }

    @Nullable
    private static List<MediaBrowserCompat.MediaItem> initializeAndroidAutoTabs(
            @Nullable List<MediaBrowserCompat.MediaItem> rootTabs) {
        // Clear saved podcast folders and update requests when the main tabs reload,
        // so they cannot be reused for another account or connection.
        playlistsSubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        REFRESH_HANDLER.removeCallbacks(REFRESH_LIBRARY);
        androidAutoHomeMediaId = null;
        cachedAndroidAutoPodcastFolders = Collections.emptyList();
        // Add Podcasts only when YTM supplies Home and Library without a Podcasts tab.
        if (rootTabs == null || rootTabs.size() != 2) return rootTabs;

        androidAutoHomeMediaId = rootTabs.get(0).a();
        List<MediaBrowserCompat.MediaItem> updatedRootTabs = new ArrayList<>(rootTabs);
        MediaDescriptionCompat podcastsDescription = new MediaDescriptionCompat(
                PODCASTS_MEDIA_ID,
                ResourceUtils.getString(PODCASTS_TITLE_RESOURCE_NAME),
                null, null, null, null, null, null);
        updatedRootTabs.add(1, new MediaBrowserCompat.MediaItem(
                podcastsDescription, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        return updatedRootTabs;
    }

    private static void refreshPodcastsAfterHomeLoad() {
        AndroidAutoSubscription subscription = podcastsSubscription;
        if (subscription == null) return;
        // New Home results contain updated podcast folder IDs, including after saving or removing a show.
        // Always queue the reload so handleAndroidAutoBrowseResult releases its lock first.
        REFRESH_HANDLER.post(() -> {
            synchronized (SupportAndroidAutoPatch.class) {
                // A new root or connection makes this saved request obsolete.
                if (podcastsSubscription != subscription) return;
            }
            subscription.reload(false);
        });
    }

    private static void cacheAndroidAutoPodcastFolders(List<MediaBrowserCompat.MediaItem> homeItems) {
        List<MediaBrowserCompat.MediaItem> folders = new ArrayList<>();
        for (MediaBrowserCompat.MediaItem item : homeItems) {
            // YTM also marks its upgrade prompt as browsable; it is not podcast content.
            if (!item.b() || UPGRADE_PROMPT_MEDIA_ID.equals(item.a())) continue;
            // Layout hints vary between Home results, so do not use them to select folders.
            // TODO: The server omits songs and playlists from Android Auto Speed dial; obtain them from phone Home.
            folders.add(item);
        }
        cachedAndroidAutoPodcastFolders = folders;
    }

    // Play a selected playlist

    /**
     * Injection point. Convert this patch's playlist media ID into YTM's command to start playback.
     *
     * <p>Show YTM's message for an empty playlist when no playable songs are found.
     * Request errors, timeouts, and missing Play commands are logged; playback is left unchanged.
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
        String playlistBrowseId = mediaId.substring(DEFERRED_PLAYLIST_MEDIA_ID_PREFIX.length());
        new PlaylistPlaybackRequest(callback, playlistBrowseId, requestGeneration).start(extras);
        return true;
    }

    /**
     * Injection point. Cancel pending playlist playback and messages when Pause/Stop is pressed.
     */
    public static void cancelPendingPlaylistPlayback() {
        // YTM cannot cancel a playback command it has not received yet.
        // Changing this number stops pending playlist responses from starting playback or showing a message.
        PLAY_REQUEST_GENERATION.incrementAndGet();
    }

    // Check playlist contents

    private static PhoneBrowseItem findFirstPlayableSong(PhoneBrowseResponse playlistResponse) {
        for (PhoneBrowseTab tab : playlistResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof PlaylistContents)) continue;
                for (PhoneBrowseItem playlistItem :
                        ((PlaylistContents) sectionContent).patch_getItems()) {
                    // The "Add a song" button has a command but no song ID; exclude it from the song check.
                    if (!playlistItem.patch_hasPlayableVideoId()) continue;
                    if (playlistItem.patch_getCommandMediaId() != null) return playlistItem;
                }
            }
        }
        return null;
    }

    // Message for an empty playlist

    /**
     * Injection point. Save YTM's media session to display the message for an empty playlist.
     */
    public static void registerPlaybackSession(@NonNull PlaybackStateSession session) {
        synchronized (PLAYBACK_SESSIONS) {
            PLAYBACK_SESSIONS.put(
                    session.patch_getPlaybackOwner(), new WeakReference<>(session));
        }
    }

    /**
     * Injection point. Get YTM's media session to display the message for an empty playlist.
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
            PlaybackCallback callback, Handler callbackHandler) {
        try {
            PlaybackStateSession session = callback.patch_getPlaybackStateSession();
            if (session == null) return;
            PlaybackStateCompat currentPlaybackState = session.patch_getPlaybackState();
            if (currentPlaybackState == null) {
                // TODO: Show the message for an empty playlist before YTM has provided any playback state.
                Logger.printDebug(() -> "No playback state for no-tracks notice");
                return;
            }
            int currentErrorCode = currentPlaybackState.f;
            // Zero means no error; preserve any error YTM is already reporting.
            if (currentErrorCode != 0) return;
            CharSequence message = ResourceUtils.getString(NO_TRACKS_MESSAGE_RESOURCE_NAME);
            PlaybackStateCompat stateWithNotice =
                    copyPlaybackStateWithNotice(currentPlaybackState, message);
            session.patch_setPlaybackState(stateWithNotice);
            callbackHandler.postDelayed(() -> {
                try {
                    // Remove this message without overwriting a newer playback state or error from YTM.
                    if (session.patch_getPlaybackState() == stateWithNotice)
                        session.patch_setPlaybackState(currentPlaybackState);
                } catch (RuntimeException ex) {
                    Logger.printException(() -> "Could not clear no-tracks notice", ex);
                }
            }, NO_TRACKS_NOTICE_MILLISECONDS);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not show no-tracks notice", ex);
        }
    }

    private static PlaybackStateCompat copyPlaybackStateWithNotice(
            PlaybackStateCompat currentPlaybackState, CharSequence message) {
        // Android Auto displays the empty playlist message through PlaybackStateCompat's error fields.
        // Keep the playing/paused status and all other fields unchanged.
        return new PlaybackStateCompat(
                currentPlaybackState.a, // Playback status
                currentPlaybackState.b, // Position
                currentPlaybackState.c, // Buffered position
                currentPlaybackState.d, // Playback speed
                currentPlaybackState.e, // Available playback actions
                EMPTY_PLAYLIST_ERROR_CODE, // Temporary error code
                message, // Temporary error message
                currentPlaybackState.h, // Position update time
                currentPlaybackState.i, // Custom actions
                currentPlaybackState.j, // Active queue item ID
                currentPlaybackState.k); // Extras
    }

    // Pending playlist playback

    private static final class PlaylistPlaybackRequest {
        private final MediaSession.Callback callback;
        private final PlaybackCallback playbackCallback;
        private final String playlistBrowseId;
        private final long requestGeneration;
        private final PhoneBrowseRequests requests;
        private final AtomicBoolean waitingForResponse = new AtomicBoolean(true);

        private PlaylistPlaybackRequest(
                MediaSession.Callback callback, String playlistBrowseId, long requestGeneration) {
            this.callback = callback;
            this.playbackCallback = (PlaybackCallback) callback;
            this.playlistBrowseId = playlistBrowseId;
            this.requestGeneration = requestGeneration;
            this.requests = phoneBrowseRequests;
        }

        private void start(@Nullable Bundle extras) {
            if (playlistBrowseId.isEmpty() || requests == null) {
                Logger.printDebug(() -> "Could not resolve selected Android Auto playlist");
                return;
            }
            Handler callbackHandler = playbackCallback.patch_getCallbackHandler();
            if (callbackHandler == null) {
                Logger.printDebug(() -> "Android Auto playback callback is no longer active");
                return;
            }
            Bundle playbackExtras = extras == null ? null : new Bundle(extras);
            // The timeout limits how long to wait for YTM's response, not how long to read its contents.
            Utils.runOnMainThreadDelayed(() -> {
                if (waitingForResponse.compareAndSet(true, false) &&
                        requestGeneration == PLAY_REQUEST_GENERATION.get()) {
                    Logger.printDebug(() -> "Selected Android Auto playlist request timed out");
                }
            }, SELECTED_PLAYLIST_LOAD_TIMEOUT_MILLISECONDS);
            try {
                ListenableFuture<PhoneBrowseResponse> future =
                        requests.patch_requestBrowse(playlistBrowseId, BACKGROUND_EXECUTOR);
                future.addListener(() -> {
                    if (!waitingForResponse.compareAndSet(true, false)) return;
                    readResponse(future, callbackHandler, playbackExtras);
                }, BACKGROUND_EXECUTOR);
            } catch (RuntimeException ex) {
                waitingForResponse.set(false);
                Logger.printException(() -> "Could not request YTM playlist: " +
                        playlistBrowseId, ex);
            }
        }

        private void readResponse(
                ListenableFuture<PhoneBrowseResponse> future, Handler callbackHandler,
                Bundle playbackExtras) {
            try {
                PhoneBrowseResponse response = future.get();
                // An empty playlist can still have a Play button.
                PhoneBrowseItem firstPlayableSong = findFirstPlayableSong(response);
                if (firstPlayableSong == null) {
                    Logger.printDebug(() ->
                            "Selected Android Auto playlist has no playable songs");
                    postToPlaybackThread(callbackHandler, () ->
                            showNoPlayableSongsNotice(playbackCallback, callbackHandler));
                    return;
                }
                // Liked Music has no Play button; start it with the first playable song.
                String nativePlayMediaId = LIKED_MUSIC_BROWSE_ID.equals(playlistBrowseId)
                        ? firstPlayableSong.patch_getCommandMediaId()
                        : response.patch_getPlaylistPlayButtonMediaId();
                if (nativePlayMediaId == null) {
                    Logger.printDebug(() -> "Selected Android Auto playlist has no playback command");
                    return;
                }
                postToPlaybackThread(callbackHandler, () ->
                        callback.onPlayFromMediaId(nativePlayMediaId, playbackExtras));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM playlist request interrupted: " +
                        playlistBrowseId, ex);
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM playlist request failed: " +
                        playlistBrowseId, ex);
            }
        }

        private void postToPlaybackThread(Handler callbackHandler, Runnable task) {
            // YTM's playback thread handles both starting music and showing a message for an empty playlist.
            callbackHandler.post(() -> {
                // Another selection, Pause/Stop, or a restarted MusicBrowserService cancels this task.
                // Check when it runs because the playback thread may have been busy since it was queued.
                if (requestGeneration != PLAY_REQUEST_GENERATION.get() ||
                        requests != phoneBrowseRequests)
                    return;
                task.run();
            });
        }
    }

}
