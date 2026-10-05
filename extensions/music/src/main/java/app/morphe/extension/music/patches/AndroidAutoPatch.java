/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.GuardedBy;
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
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Supplies Android Auto Home and Library with content from YTM's phone app.
 * Podcasts uses the folders from YTM's original Android Auto Home response.
 */
@SuppressWarnings("unused")
public final class AndroidAutoPatch {

    public interface PhoneBrowseClient {
        Object patch_accountScope();
        ListenableFuture<PhoneBrowseResponse> patch_requestBrowse(
                String browseId, @Nullable String continuation, Executor executor);
        ListenableFuture<PhoneBrowseResponse> patch_requestLibraryPagination(
                Object paginationCommand, Executor executor);
    }

    /**
     * The first page contains TabRenderer wrappers and sections;
     * later pages use {@link #patch_getPaginatedLibraryGrid}. TabRenderer describes the phone page,
     * not Android Auto's tabs.
     */
    public interface PhoneBrowseResponse {
        byte[] patch_responseBytes();
        Iterable<PhoneBrowseTab> patch_getTabs();
        @Nullable GridRenderer patch_getPaginatedLibraryGrid();
    }

    public interface PhoneBrowseTab {
        @Nullable Iterable<?> patch_getSectionContents();
    }

    public interface GridRenderer {
        /**
         * Includes artists and podcasts as well as playlists; filter before returning playlists to Android Auto.
         */
        Iterable<?> patch_getItems();
        /**
         * YTM's pagination commands: NEXT requests the next Library page; RELOAD refreshes the list.
         */
        Iterable<?> patch_getPaginationCommands();
    }

    public interface AndroidAutoBrowseRequest {
        @Nullable String patch_getRequestedMediaId();

        /**
         * The Android Auto connection for this request, or null if unknown.
         */
        @Nullable Object patch_getBrowserConnection();

        /**
         * Sends the list through YTM, including the hook for
         * {@link AndroidAutoPatch#handleAndroidAutoBrowseResult}.
         * YTM may remove items to keep the returned list within its byte limit.
         */
        void patch_deliverAndroidAutoItems(List<MediaBrowserCompat.MediaItem> androidAutoItems);
    }

    public interface AndroidAutoFolderReload {
        void patch_reloadFolder(String parentMediaId, Object connection, @Nullable Bundle options);
    }

    public interface PhoneBrowseItem {
        /**
         * Returns a playlist page ID, or null if none is found or the item's commands identify different playlists.
         * A command that does not open a page makes YTM's converter throw;
         * {@link AndroidAutoPatch#collectPlaylistsFromGrid} skips that item.
         */
        @Nullable String patch_getPlaylistBrowseId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    private static final class PlaylistsFolderLoad {
        // Use the same YTM object throughout pagination, even if MusicBrowserService restarts.
        private final PhoneBrowseClient phoneBrowseClient;
        private final long loadNumber = playlistsLoadCounter.incrementAndGet();
        private final PlaylistsFolderDelivery folderDelivery;
        @GuardedBy("this")
        private final List<MediaBrowserCompat.MediaItem> libraryPlaylists = new ArrayList<>();
        @GuardedBy("this")
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        @GuardedBy("this")
        private boolean deliveryPreparationStarted;

        private PlaylistsFolderLoad(PhoneBrowseClient browseClient, PlaylistsFolderDelivery folderDelivery) {
            this.phoneBrowseClient = browseClient;
            this.folderDelivery = folderDelivery;
        }

        /**
         * Allows only one delivery attempt, whether pagination finishes, fails, or times out.
         *
         * @return a copy of the collected playlists, possibly empty; null if delivery preparation already started
         */
        @Nullable
        private synchronized List<MediaBrowserCompat.MediaItem> takePlaylistsForDelivery() {
            if (deliveryPreparationStarted) return null;
            deliveryPreparationStarted = true;
            return new ArrayList<>(libraryPlaylists);
        }
    }

    /** Prevents older results from replacing newer results for the same folder and Android Auto connection. */
    private static final class PlaylistsFolderDelivery {
        @GuardedBy("this")
        private long lastDeliveredLoadNumber;

        // Keep the check, send, and update together so another load cannot send between them.
        private synchronized void deliver(long loadNumber, AndroidAutoBrowseRequest androidAutoRequest,
                                          List<MediaBrowserCompat.MediaItem> androidAutoPlaylistItems, String logReason) {
            // A newer load still in progress does not prevent this one from returning playlists.
            if (loadNumber < lastDeliveredLoadNumber) return;
            Logger.printDebug(() -> "YTM Library: " + logReason + "; returning: " +
                    androidAutoPlaylistItems.size() + " playlists");
            try {
                androidAutoRequest.patch_deliverAndroidAutoItems(androidAutoPlaylistItems);
                // A failed send must not prevent an older load from returning its playlists.
                lastDeliveredLoadNumber = loadNumber;
            } catch (RuntimeException ex) {
                // Do not retry: YTM may already consider this request answered.
                // Reopening Library or Playlists starts a new request.
                Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
            }
        }
    }

    /**
     * Saves an Android Auto list's ID, service, and connection so its contents can be requested again.
     */
    private record AndroidAutoSubscription(WeakReference<AndroidAutoFolderReload> browserService,
                                           String parentMediaId, WeakReference<Object> connection) {
        private AndroidAutoSubscription(AndroidAutoFolderReload browserService,
                                        String parentMediaId, Object connection) {
            this(new WeakReference<>(browserService), parentMediaId, new WeakReference<>(connection));
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

    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    public static final String PLAYLISTS_TITLE_RESOURCE_STRING
            = ResourceUtils.getString("library_playlists_shelf_title");
    // Return collected playlists when this timeout expires.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 30_000;
    private static final int LIBRARY_REFRESH_DELAY_MILLISECONDS = 5_000;
    private static final String ANDROID_AUTO_ROOT_MEDIA_ID = "com.google.android.projection.gearhead";
    private static final String PODCASTS_MEDIA_ID = "morphe:aa:podcasts";
    private static final String LIBRARY_PREFIX = "morphe-aa-library://collection/";

    private enum LibraryFolder {
        LAST_PLAYED("FEmusic_history", "morphe_music_android_auto_last_played"),
        PLAYLISTS(PHONE_LIBRARY_BROWSE_ID, "library_playlists_shelf_title"),
        ALBUMS("FEmusic_liked_albums", "library_albums_shelf_title"),
        ARTISTS("FEmusic_library_corpus_track_artists", "library_artists_shelf_title");

        final String browseId;
        final String titleResource;

        LibraryFolder(String browseId, String titleResource) {
            this.browseId = browseId;
            this.titleResource = titleResource;
        }

        String mediaId() { return LIBRARY_PREFIX + name(); }
    }
    private static final String PODCASTS_TITLE_RESOURCE_NAME = "offline_podcasts_shelf_title";
    private static final String UPGRADE_PROMPT_MEDIA_ID = "promotion_version_1";
    private static final String FORCE_REFRESH = "com.google.android.apps.youtube.music.mediabrowser.force_refresh";
    
    static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    // Keep a Handler so timeouts and delayed refreshes can be cancelled.
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH_LIBRARY = AndroidAutoPatch::refreshAndroidAutoLibrary;
    
    // A user's playlist can also be named "Playlists"; do not use these title matches for playback.
    private static final Set<String> playlistsTitleMatchMediaIds = ConcurrentHashMap.newKeySet();
    // Give each Library load a number to distinguish earlier requests from later requests.
    private static final AtomicLong playlistsLoadCounter = new AtomicLong();
    // Remember which Library load last updated each folder on each Android Auto connection.
    
    @GuardedBy("itself")
    private static final WeakHashMap<Object, Map<String, PlaylistsFolderDelivery>>
            playlistsFolderDeliveries = new WeakHashMap<>();
    
    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static AndroidAutoSubscription librarySubscription;
    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static AndroidAutoSubscription podcastsSubscription;
    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static AndroidAutoSubscription homeSubscription;
    @GuardedBy("AndroidAutoPatch.class")
    private static String androidAutoHomeMediaId;
    @Nullable
    private static volatile String androidAutoLibraryMediaId;
    @GuardedBy("AndroidAutoPatch.class")
    private static List<MediaBrowserCompat.MediaItem> cachedAndroidAutoPodcastFolders = Collections.emptyList();

    @Nullable
    private static volatile PhoneBrowseClient phoneBrowseClient;

    private AndroidAutoPatch() {
    }

    // Capture YTM's Library request methods and identify the Playlists folder

    /**
     * Injection point. Save the phone browse client obtained during MusicBrowserService initialization.
     * Clear saved requests and pending refreshes so they cannot use the previous service's connection.
     */
    public static synchronized void setPhoneBrowseClient(PhoneBrowseClient client) {
        phoneBrowseClient = client;
        androidAutoLibraryMediaId = null;
        librarySubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        MAIN.removeCallbacks(REFRESH_LIBRARY);
        Logger.printDebug(() -> "Ready to request phone Library contents: " +
                client.getClass().getName());
    }

    /**
     * Injection point. Identify the Playlists folder by its translated title; its ID varies.
     */
    public static void rememberPlaylistsTitleMatch(@Nullable String androidAutoMediaId,
                                                   @Nullable CharSequence title) {
        if (title == null || (PLAYLISTS_TITLE_RESOURCE_STRING != null
                && !PLAYLISTS_TITLE_RESOURCE_STRING.contentEquals(title))) return;
        if (androidAutoMediaId != null) playlistsTitleMatchMediaIds.add(androidAutoMediaId);
    }

    // Intercept Android Auto requests for Library or the Playlists folder

    /**
     * YTM has already called detach() on the result, so it can be sent after this method returns.
     * Failed or timed-out playlist loads return the items collected so far.
     *
     * @return true if the patch will answer the request; false to leave it to YTM.
     */
    public static boolean handleAndroidAutoPlaylists(AndroidAutoBrowseRequest androidAutoRequest) {
        try {
            PhoneBrowseClient browseClient = phoneBrowseClient;
            if (browseClient == null) return false;
            String requestedMediaId = androidAutoRequest.patch_getRequestedMediaId();
            if (requestedMediaId == null) return false;
            if (requestedMediaId.equals(androidAutoLibraryMediaId)) {
                PreparedItems folders = new PreparedItems();
                for (LibraryFolder folder : LibraryFolder.values()) {
                    folders.add(folder.mediaId(), ResourceUtils.getString(folder.titleResource), "", "", false);
                }
                androidAutoRequest.patch_deliverAndroidAutoItems(folders);
                return true;
            }
            if (requestedMediaId.startsWith(LIBRARY_PREFIX)
                    && !requestedMediaId.equals(LibraryFolder.PLAYLISTS.mediaId())) {
                CollectionLoad load = new CollectionLoad(androidAutoRequest);
                MAIN.postDelayed(load.timeout, DEADLINE_MS);
                BACKGROUND_EXECUTOR.execute(() -> load.start(requestedMediaId));
                return true;
            }
            if (!requestedMediaId.equals(LibraryFolder.PLAYLISTS.mediaId())
                    && !playlistsTitleMatchMediaIds.contains(requestedMediaId)) return false;

            Object connection = androidAutoRequest.patch_getBrowserConnection();
            PlaylistsFolderDelivery folderDelivery;
            synchronized (playlistsFolderDeliveries) {
                if (connection == null) {
                    // Without the connection, there is no way to identify other requests for this same folder.
                    folderDelivery = new PlaylistsFolderDelivery();
                } else {
                    // Reopening Library or Playlists, or refreshing either, can overlap an unfinished load.
                    folderDelivery = playlistsFolderDeliveries
                            .computeIfAbsent(connection, ignored -> new HashMap<>())
                            .computeIfAbsent(requestedMediaId, ignored -> new PlaylistsFolderDelivery());
                }
            }

            PlaylistsFolderLoad load = new PlaylistsFolderLoad(browseClient, folderDelivery);
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

    /**
     * Reads Library responses on background threads and follows pagination until no command remains.
     * The first page and later pages have different response formats and need separate parsers.
     */
    private static void requestLibraryPage(AndroidAutoBrowseRequest androidAutoRequest,
                                           PlaylistsFolderLoad load,
                                           @Nullable Object paginationCommand) {
        boolean firstPage = paginationCommand == null;
        ListenableFuture<PhoneBrowseResponse> libraryResponseFuture = firstPage
                ? load.phoneBrowseClient.patch_requestBrowse(
                        PHONE_LIBRARY_BROWSE_ID, null, BACKGROUND_EXECUTOR)
                : load.phoneBrowseClient.patch_requestLibraryPagination(
                        paginationCommand, BACKGROUND_EXECUTOR);
        libraryResponseFuture.addListener(() -> {
            synchronized (load) {
                if (load.deliveryPreparationStarted) return;
            }
            try {
                PhoneBrowseResponse libraryResponse = libraryResponseFuture.get();
                Object nextPaginationCommand = firstPage
                        ? appendInitialLibraryPlaylists(libraryResponse, load)
                        : appendPaginatedLibraryPlaylists(libraryResponse, load);
                synchronized (load) {
                    // The timeout may have started returning playlists while this Library response was being read.
                    if (load.deliveryPreparationStarted) return;
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

    private static Object appendInitialLibraryPlaylists(PhoneBrowseResponse libraryResponse,
                                                        PlaylistsFolderLoad load) {
        Object paginationCommand = null;
        for (PhoneBrowseTab tab : libraryResponse.patch_getTabs()) {
            Iterable<?> sectionContents = tab.patch_getSectionContents();
            if (sectionContents == null) continue;
            for (Object sectionContent : sectionContents) {
                if (!(sectionContent instanceof GridRenderer gridRenderer)) continue;
                collectPlaylistsFromGrid(gridRenderer, load);
                if (paginationCommand == null) {
                    paginationCommand = firstPaginationCommand(gridRenderer);
                }
            }
        }

        Logger.printDebug(() -> {
            synchronized (load) {
                return "Found playlists in phone Library: " + load.libraryPlaylists.size();
            }
        });
        return paginationCommand;
    }

    private static Object appendPaginatedLibraryPlaylists(PhoneBrowseResponse libraryResponse,
                                                          PlaylistsFolderLoad load) {
        GridRenderer gridRenderer = libraryResponse.patch_getPaginatedLibraryGrid();
        // An unrecognized pagination result ends loading; keep the playlists collected so far.
        if (gridRenderer == null) return null;
        collectPlaylistsFromGrid(gridRenderer, load);
        Logger.printDebug(() -> {
            synchronized (load) {
                return "Found playlists in phone Library: " + load.libraryPlaylists.size();
            }
        });
        return firstPaginationCommand(gridRenderer);
    }

    /** Skips unreadable Library items so one failure does not discard the remaining playlists. */
    private static void collectPlaylistsFromGrid(GridRenderer gridRenderer,
                                                 PlaylistsFolderLoad load) {
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

    /** Injection point. Exclude artists and shows; skip commands that identify different playlists. */
    @Nullable
    public static String resolvePlaylistBrowseId(@Nullable String firstBrowseId,
                                                 @Nullable String secondBrowseId) {
        if (firstBrowseId != null && !firstBrowseId.startsWith("VL")) firstBrowseId = null;
        if (secondBrowseId != null && !secondBrowseId.startsWith("VL")) secondBrowseId = null;
        if (firstBrowseId == null) return secondBrowseId;
        if (secondBrowseId == null || firstBrowseId.equals(secondBrowseId)) return firstBrowseId;
        return null;
    }

    /**
     * Builds playable cards from Library items. YTM's playback command accepts a playlist ID without
     * a song ID, so building a card does not require fetching the playlist's songs.
     */
    private static void addLibraryPlaylist(PhoneBrowseItem libraryItem,
                                           PlaylistsFolderLoad load) {
        String playlistBrowseId = libraryItem.patch_getPlaylistBrowseId();
        if (playlistBrowseId == null) return;
        // TODO: Verify playlist-ID playback for Episodes for Later (VLSE).
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(playlistBrowseId)) return;

        CharSequence titleText = libraryItem.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        // Phone page IDs prefix playback playlist IDs with "VL".
        String mediaId = createPlaylistMediaId(playlistBrowseId.substring(2));
        // Keep the playlist available if its subtitle or artwork cannot be read.
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitleOrEmpty(libraryItem), null, null,
                artworkUriOrNull(libraryItem), null, null);
        MediaBrowserCompat.MediaItem playlist = new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
        // Metadata reads stay outside the lock so they cannot delay timeout handling.
        synchronized (load) {
            if (load.deliveryPreparationStarted || !load.seenPlaylistBrowseIds.add(playlistBrowseId)) {
                return;
            }
            load.libraryPlaylists.add(playlist);
        }
    }

    private static String subtitleOrEmpty(PhoneBrowseItem libraryItem) {
        try {
            CharSequence subtitle = libraryItem.patch_getSubtitle();
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ex) {
            Logger.printInfo(() -> "Could not read playlist subtitle; leaving it blank", ex);
            return "";
        }
    }

    private static Uri artworkUriOrNull(PhoneBrowseItem libraryItem) {
        try {
            return libraryItem.patch_getArtworkUri();
        } catch (RuntimeException ex) {
            Logger.printInfo(() -> "Could not read playlist artwork; leaving it unset", ex);
            return null;
        }
    }

    // Return playlists to Android Auto

    /**
     * Stops collection before sending the result so a concurrent timeout cannot send it twice.
     */
    private static void deliverAndroidAutoPlaylists(AndroidAutoBrowseRequest androidAutoRequest,
                                                    PlaylistsFolderLoad load, String logReason) {
        List<MediaBrowserCompat.MediaItem> collectedPlaylists = load.takePlaylistsForDelivery();
        if (collectedPlaylists == null) return;
        load.folderDelivery.deliver(load.loadNumber, androidAutoRequest, collectedPlaylists, logReason);
    }

    /** Replaced during patching with calls to YTM's playlist command builder and Android Auto media ID encoder. */
    static String createPlaylistMediaId(String playlistId) {
        return null;
    }

    // Refresh after Library changes

    public static synchronized void rememberAndroidAutoSubscription(AndroidAutoFolderReload browserService,
                                                                    @Nullable String parentMediaId,
                                                                    Object connection) {
        try {
            if (parentMediaId == null) return;
            if (parentMediaId.startsWith(LIBRARY_PREFIX)
                    || playlistsTitleMatchMediaIds.contains(parentMediaId)) {
                librarySubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
            } else if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
                podcastsSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
            } else if (parentMediaId.equals(androidAutoHomeMediaId)) {
                homeSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "rememberAndroidAutoSubscription failure", ex);
        }
    }

    /** Injection point. Refresh Android Auto after a successful request changes the Library. */
    public static void onRequestSucceeded(@Nullable String endpoint) {
        if (endpoint == null) return;
        try {
            switch (endpoint) {
                case "browse/edit_playlist":
                case "like/like":
                case "like/removelike":
                case "playlist/create":
                case "playlist/delete":
                    scheduleLibraryRefresh();
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not schedule Android Auto Library refresh", ex);
        }
    }

    /** Combines completed Library changes into one delayed refresh. */
    private static synchronized void scheduleLibraryRefresh() {
        if (librarySubscription == null && homeSubscription == null) return;
        // Wait for updated playlist artwork before refreshing.
        MAIN.removeCallbacks(REFRESH_LIBRARY);
        MAIN.postDelayed(REFRESH_LIBRARY, LIBRARY_REFRESH_DELAY_MILLISECONDS);
    }

    private static void refreshAndroidAutoLibrary() {
        AndroidAutoSubscription library;
        AndroidAutoSubscription home;
        // Refresh the current connection if Android Auto reconnected during the delay.
        synchronized (AndroidAutoPatch.class) {
            library = librarySubscription;
            home = homeSubscription;
        }
        // Reloading needs YTM's lock; YTM's result delivery needs this class's lock.
        // Reload outside synchronized to avoid the two threads waiting for each other.
        if (library != null) library.reload(false);
        // Force a new Home request to include show changes; its response refreshes Podcasts.
        if (home != null) home.reload(true);
    }

    // Home and Podcasts

    public static boolean isCustomAndroidAutoPageId(@Nullable String mediaId) {
        return PODCASTS_MEDIA_ID.equals(mediaId)
                || mediaId != null && mediaId.startsWith(LIBRARY_PREFIX);
    }

    /**
     * Injection point. Modify lists before YTM sends them to Android Auto.
     * Add a Podcasts tab, cache Home's podcast folders, and return them when that tab is requested.
     * New Home results also refresh Podcasts if it has been opened.
     */
    @Nullable
    public static synchronized List<MediaBrowserCompat.MediaItem> handleAndroidAutoBrowseResult(
            AndroidAutoBrowseRequest request, @Nullable List<MediaBrowserCompat.MediaItem> ytmItems) {
        try {
            String parentMediaId = request.patch_getRequestedMediaId();
            if (ANDROID_AUTO_ROOT_MEDIA_ID.equals(parentMediaId)) {
                return initializeAndroidAutoTabs(ytmItems);
            }
            if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
                return new ArrayList<>(cachedAndroidAutoPodcastFolders);
            }
            if (parentMediaId != null && parentMediaId.equals(androidAutoHomeMediaId)
                    && ytmItems != null && !(ytmItems instanceof PreparedItems)) {
                cacheAndroidAutoPodcastFolders(ytmItems);
                refreshPodcastsAfterHomeLoad();
                // Home will load the phone music feed; keep these folders for the Podcasts tab.
                return Collections.emptyList();
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "handleAndroidAutoBrowseResult failure", ex);
        }
        return ytmItems;
    }

    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static List<MediaBrowserCompat.MediaItem> initializeAndroidAutoTabs(
            @Nullable List<MediaBrowserCompat.MediaItem> rootTabs) {
        // Do not reuse saved requests or podcast content after the main tabs reload.
        librarySubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        MAIN.removeCallbacks(REFRESH_LIBRARY);
        androidAutoHomeMediaId = null;
        androidAutoLibraryMediaId = null;
        cachedAndroidAutoPodcastFolders = Collections.emptyList();

        // Add Podcasts only when YTM supplies Home and Library without a Podcasts tab.
        if (rootTabs == null || rootTabs.size() != 2) return rootTabs;

        androidAutoHomeMediaId = rootTabs.get(0).a();
        androidAutoLibraryMediaId = rootTabs.get(1).a();
        List<MediaBrowserCompat.MediaItem> updatedRootTabs = new ArrayList<>(rootTabs);
        MediaDescriptionCompat podcastsDescription = new MediaDescriptionCompat(
                PODCASTS_MEDIA_ID,
                ResourceUtils.getString(PODCASTS_TITLE_RESOURCE_NAME),
                null, null, null, null, null, null);
        updatedRootTabs.add(1, new MediaBrowserCompat.MediaItem(
                podcastsDescription, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        return updatedRootTabs;
    }

    @GuardedBy("AndroidAutoPatch.class")
    private static void refreshPodcastsAfterHomeLoad() {
        AndroidAutoSubscription subscription = podcastsSubscription;
        if (subscription == null) return;
        // New Home results can change the podcast folder IDs.
        // Always queue the reload so handleAndroidAutoBrowseResult releases its lock first.
        MAIN.post(() -> {
            synchronized (AndroidAutoPatch.class) {
                // Reloading the main tabs or reconnecting replaces the saved Podcasts request.
                if (podcastsSubscription != subscription) return;
            }
            subscription.reload(false);
        });
    }

    @GuardedBy("AndroidAutoPatch.class")
    private static void cacheAndroidAutoPodcastFolders(List<MediaBrowserCompat.MediaItem> homeItems) {
        List<MediaBrowserCompat.MediaItem> folders = new ArrayList<>();
        // Some podcast lists have no layout hint; include them if they are browsable.
        for (MediaBrowserCompat.MediaItem item : homeItems) {
            // YTM's upgrade prompt is also browsable; exclude it explicitly.
            if (!item.b() || UPGRADE_PROMPT_MEDIA_ID.equals(item.a())) continue;
            folders.add(item);
        }
        cachedAndroidAutoPodcastFolders = folders;
    }

    public static boolean loadAndroidAutoHome(AndroidAutoBrowseRequest request,
                                               @Nullable List<MediaBrowserCompat.MediaItem> nativeItems) {
        if (phoneBrowseClient == null
                || nativeItems == null || !Objects.equals(request.patch_getRequestedMediaId(), androidAutoHomeMediaId)
                || androidAutoHomeMediaId == null) return false;
        // Sending Home results calls this method again; skip our completed list to avoid another load.
        if (nativeItems instanceof PreparedItems) return false;
        HomeLoad load = new HomeLoad(request);
        MAIN.postDelayed(load.timeout, DEADLINE_MS);
        BACKGROUND_EXECUTOR.execute(load::start);
        return true;
    }

    private static final class HomeLoad extends Load {
        final AndroidAutoBrowseRequest request;

        HomeLoad(AndroidAutoBrowseRequest request) {
            super(phoneBrowseClient);
            this.request = request;
        }

        void start() {
            if (finished.get()) return;
            try {
                if (!current(scope)) { finish(Collections.emptyList()); return; }
                PhoneBrowseClient client = (PhoneBrowseClient) scope.client();
                await(requestItems("home", scope,
                        () -> client.patch_requestBrowse("FEmusic_home", null, BACKGROUND_EXECUTOR),
                        response -> AndroidAutoResponseParser.home(response.patch_responseBytes()),
                        page -> mediaItems(page, 2)));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load Android Auto Home music", ex);
                finish(Collections.emptyList());
            }
        }

        @Override void deliver(List<MediaBrowserCompat.MediaItem> items) {
            PreparedItems home = new PreparedItems();
            if (current(scope)) home.addAll(items);
            request.patch_deliverAndroidAutoItems(home);
        }
    }

    // Library collections

    private static final class CollectionLoad extends Load {
        final AndroidAutoBrowseRequest request;

        CollectionLoad(AndroidAutoBrowseRequest request) {
            super(phoneBrowseClient);
            this.request = request;
        }

        void start(String id) {
            if (finished.get()) return;
            try {
                if (id.length() > MAX_NAVIGATION_LENGTH || !current(scope)) {
                    finish(Collections.emptyList()); return;
                }
                Uri uri = Uri.parse(id);
                LibraryFolder folder = LibraryFolder.valueOf(uri.getLastPathSegment());
                String continuation = uri.getQueryParameter("c");
                PhoneBrowseClient client = (PhoneBrowseClient) scope.client();
                await(requestItems(id, scope,
                        () -> client.patch_requestBrowse(folder.browseId, continuation, BACKGROUND_EXECUTOR),
                        response -> AndroidAutoResponseParser.collection(response.patch_responseBytes()),
                        page -> {
                            PreparedItems items = mediaItems(page,
                                    folder == LibraryFolder.LAST_PLAYED ? 1 : 2);
                            if (!page.continuation().isEmpty() && !page.continuation().equals(continuation)) {
                                String next = Uri.parse(folder.mediaId()).buildUpon()
                                        .appendQueryParameter("c", page.continuation()).build().toString();
                                addMoreResults(items, next);
                            }
                            return items;
                        }));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load Android Auto Library collection", ex);
                finish(Collections.emptyList());
            }
        }

        @Override void deliver(List<MediaBrowserCompat.MediaItem> items) {
            request.patch_deliverAndroidAutoItems(items);
        }
    }

    static final long DEADLINE_MS = 8_000;
    private static final Requests<RequestKey, List<MediaBrowserCompat.MediaItem>> REQUESTS = new Requests<>();
    private static final int MAX_COMMAND_BYTES = 16 * 1024;
    static final int MAX_NAVIGATION_LENGTH = 24 * 1024;

    // Media items, artwork, and playback commands

    static final class PreparedItems extends ArrayList<MediaBrowserCompat.MediaItem> {
        final int itemStyle;

        PreparedItems() { this(1); }
        PreparedItems(int itemStyle) { this.itemStyle = itemStyle; }

        void add(String id, String title, String subtitle, String artwork, boolean playable) {
            if (id == null || id.length() > MAX_NAVIGATION_LENGTH) return;
            Bundle extras = new Bundle();
            extras.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", itemStyle);
            add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat(
                    id, title, subtitle, null, null, artwork.isEmpty() ? null : Uri.parse(artwork), extras, null),
                    playable ? MediaBrowserCompat.MediaItem.FLAG_PLAYABLE : MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }
    }

    private static PreparedItems mediaItems(AndroidAutoResponseParser.Page page,
                                            int itemStyle) {
        PreparedItems items = new PreparedItems(itemStyle);
        Set<String> seen = new HashSet<>();
        for (AndroidAutoResponseParser.Item row : page.items()) {
            byte[] command = row.playable() ? row.command() : row.playCommand();
            if (command.length > MAX_COMMAND_BYTES) continue;
            var endpoint = new AndroidAutoResponseParser.Message(command);
            if (!AndroidAutoResponseParser.isPlayable(endpoint)) continue;
            String playlistId = endpoint.message(AndroidAutoResponseParser.WATCH_PLAYLIST).string(1);
            // A menu's playlist action may shuffle; use ordered playback for albums and playlists.
            // Preserve YTM's "Start radio" action, which plays a generated mix.
            String id = !row.playable() && !playlistId.isEmpty() && !playlistId.startsWith("RD")
                    ? createPlaylistMediaId(playlistId) : encodeCommand(command);
            if (id == null || !seen.add(id)) continue;
            items.add(id, row.title(), row.subtitle(), row.artworkUrl(), true);
        }
        return items;
    }

    private static void addMoreResults(PreparedItems items, String id) {
        items.add(id, ResourceUtils.getString("morphe_music_android_auto_more_results"), "",
                "android.resource://android/" + android.R.drawable.ic_media_next, false);
    }

    /** Implemented by installAndroidAutoCommandEncoder during patching. */
    private static String encodeCommand(byte[] command) {
        throw new UnsupportedOperationException("Android Auto command bridge was not installed");
    }

    // Request sharing, timeouts, and cancellation

    record Scope(Object client, Object account, String locale) {
        boolean matches(Scope other) {
            return other != null && client == other.client && Objects.equals(account, other.account)
                    && locale.equals(other.locale);
        }
    }

    private static Object account(Object client) {
        return client instanceof PhoneBrowseClient browse ? browse.patch_accountScope() : null;
    }

    static Scope scope(Object client) {
        return new Scope(client, account(client), Utils.getContext().getResources()
                .getConfiguration().getLocales().toLanguageTags());
    }

    static boolean current(Scope captured) {
        Object client = captured.client();
        return client != null && client == phoneBrowseClient && captured.matches(scope(client));
    }

    record RequestKey(String id, Scope scope) {
        @Override public boolean equals(Object other) {
            return other instanceof RequestKey key && id.equals(key.id) && scope.matches(key.scope);
        }
        @Override public int hashCode() {
            return Objects.hash(id, System.identityHashCode(scope.client()), scope.account(),
                    scope.locale());
        }
    }

    private static <T> Requests<RequestKey, List<MediaBrowserCompat.MediaItem>>.Lease requestItems(
            String key, Scope scope, Supplier<ListenableFuture<T>> fetch,
            Function<T, AndroidAutoResponseParser.Page> parse,
            Function<AndroidAutoResponseParser.Page, List<MediaBrowserCompat.MediaItem>> convert) {
        return REQUESTS.acquire(scope.account() == null ? null : new RequestKey(key, scope),
                () -> Requests.transform(fetch.get(),
                        response -> convert.apply(parse.apply(response)), BACKGROUND_EXECUTOR));
    }

    /** Each caller completes once and has its own timeout, even when requests are shared. */
    abstract static class Load {
        final Scope scope;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Requests<RequestKey, List<MediaBrowserCompat.MediaItem>>.Lease lease;
        final Runnable timeout = () -> finish(Collections.emptyList());

        Load(Object client) { scope = scope(client); }

        void await(Requests<RequestKey, List<MediaBrowserCompat.MediaItem>>.Lease work) {
            lease = work;
            if (finished.get()) { work.close(); return; }
            work.future.whenComplete((items, error) -> {
                if (finished.get()) return;
                try {
                    if (error != null) throw new java.util.concurrent.CompletionException(error);
                    finish(items);
                }
                catch (Exception ex) {
                    Logger.printException(() -> "Could not load Android Auto content", ex);
                    finish(Collections.emptyList());
                }
            });
        }

        void finish(List<MediaBrowserCompat.MediaItem> items) {
            if (!finished.compareAndSet(false, true)) return;
            MAIN.removeCallbacks(timeout);
            cleanup();
            var work = lease;
            if (work != null) {
                if (!work.future.isDone()) work.future.cancel(true);
                work.close();
            }
            MAIN.post(() -> {
                try { deliver(current(scope) ? items : Collections.emptyList()); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto results", ex); }
            });
        }

        void cleanup() {}
        abstract void deliver(List<MediaBrowserCompat.MediaItem> items);
    }

    /** Shares pending work only; completed results are never reused. */
    static final class Requests<K, V> {
        private final Map<K, Work> pending = new HashMap<>();

        // Avoid Futures.transform: YTM's bundled Guava can throw NoSuchMethodError.
        static <T, R> CompletableFuture<R> transform(ListenableFuture<T> source,
                                                    Function<T, R> convert, Executor executor) {
            CompletableFuture<R> result = new CompletableFuture<>();
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) source.cancel(true);
            });
            source.addListener(() -> {
                if (result.isDone()) return;
                try { result.complete(convert.apply(source.get())); }
                catch (Exception ex) { result.completeExceptionally(ex); }
            }, executor);
            return result;
        }

        private final class Work {
            final K key;
            final CompletableFuture<V> future;
            int users;

            Work(K key, CompletableFuture<V> future) {
                this.key = key;
                this.future = future;
            }
        }

        final class Lease implements AutoCloseable {
            final CompletableFuture<V> future;
            private Work work;

            Lease(Work work) {
                this.work = work;
                work.users++;
                // A separate future lets one caller time out without cancelling the others.
                future = work.future.thenApply(Function.identity());
            }

            @Override
            public void close() {
                Work released;
                synchronized (Requests.this) {
                    released = work;
                    if (released == null) return;
                    work = null;
                    if (--released.users != 0) return;
                    pending.remove(released.key, released);
                }
                if (!released.future.isDone()) released.future.cancel(true);
            }
        }

        synchronized Lease acquire(K key, Supplier<CompletableFuture<V>> start) {
            Work work = key == null ? null : pending.get(key);
            if (work == null || work.future.isDone()) {
                work = new Work(key, start.get());
                if (key != null) pending.put(key, work);
            }
            return new Lease(work);
        }
    }
}
