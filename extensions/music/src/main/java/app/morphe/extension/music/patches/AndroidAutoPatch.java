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

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * The Podcasts tab uses folders from YTM's original Android Auto Home response.
 */
@SuppressWarnings("unused")
public final class AndroidAutoPatch {

    public interface PhoneBrowseClient {
        /** Current account used to construct YTM's browse requests. */
        Object patch_accountScope();
        ListenableFuture<PhoneBrowseResponse> patch_requestBrowse(
                String browseId, Executor executor);
        ListenableFuture<PhoneBrowseResponse> patch_requestBrowseContinuation(
                String continuation, Executor executor);
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

        private synchronized boolean isDeliveryPreparationStarted() {
            return deliveryPreparationStarted;
        }

        private synchronized void addPlaylistIfNew(String browseId, MediaBrowserCompat.MediaItem playlist) {
            if (deliveryPreparationStarted || !seenPlaylistBrowseIds.add(browseId)) {
                return;
            }
            libraryPlaylists.add(playlist);
        }

        private synchronized int getPlaylistCount() {
            return libraryPlaylists.size();
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
    private static final String SPEED_DIAL_MEDIA_ID = "morphe:aa:speed-dial";
    private static final String SPEED_DIAL_BROWSE_ID = "FEmusic_listen_again";
    private static final String HOME_SECTION_PREFIX = "morphe-aa-home://section/";
    private static final String PODCAST_COLLECTION_PREFIX = "morphe-aa-podcast://collection/";
    private static final String LIBRARY_PREFIX = "morphe-aa-library://collection/";

    private enum LibraryFolder {
        LAST_PLAYED("FEmusic_history", "morphe_music_android_auto_last_played", "history"),
        PLAYLISTS(PHONE_LIBRARY_BROWSE_ID, "library_playlists_shelf_title", "playlist"),
        ALBUMS("FEmusic_liked_albums", "library_albums_shelf_title", "album"),
        SONGS("FEmusic_liked_videos", "library_songs_shelf_title", "audio"),
        ARTISTS("FEmusic_library_corpus_track_artists", "library_artists_shelf_title", "person_audio"),
        SUBSCRIPTIONS("FEmusic_library_corpus_artists", "morphe_music_android_auto_subscriptions", "subscriptions"),
        PODCASTS("FEmusic_library_non_music_audio_list", "offline_podcasts_shelf_title", "podcast"),
        UPLOADED_ALBUMS("FEmusic_library_privately_owned_releases", "library_uploads_title", "upload"),
        UPLOADED_SONGS("FEmusic_library_privately_owned_tracks", "library_uploads_title", "upload");

        final String browseId;
        final String titleResource;
        final String iconName;

        LibraryFolder(String browseId, String titleResource, String iconName) {
            this.browseId = browseId;
            this.titleResource = titleResource;
            this.iconName = iconName;
        }

        String mediaId() { return LIBRARY_PREFIX + name(); }

        String artworkUri() {
            // Native Auto uses YTM's drawables; resolve them in the patched app's package.
            return "android.resource://" + Utils.getContext().getPackageName()
                    + "/drawable/yt_outline_experimental_" + iconName + "_vd_theme_24";
        }

        MediaBrowserCompat.MediaItem mediaItem() {
            Bundle extras = new Bundle();
            boolean uploads = this == UPLOADED_ALBUMS || this == UPLOADED_SONGS;
            extras.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", 1);
            extras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", this == LAST_PLAYED || uploads ? 1 : 3);
            extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", this == LAST_PLAYED || this == PODCASTS ? 1 : 2);
            if (this == LAST_PLAYED) extras.putBoolean("com.google.android.apps.youtube.music.mediabrowser.IS_LAST_PLAYED", true);
            return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat(
                    mediaId(), ResourceUtils.getString(titleResource), null, null, null,
                    Uri.parse(artworkUri()), extras, null), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
        }
    }
    private static final String PODCASTS_TITLE_RESOURCE_NAME = "offline_podcasts_shelf_title";
    private static final String UPGRADE_PROMPT_MEDIA_ID = "promotion_version_1";
    private static final String FORCE_REFRESH = "com.google.android.apps.youtube.music.mediabrowser.force_refresh";
    static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    static final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH_LIBRARY = AndroidAutoPatch::refreshAndroidAutoLibrary;

    // A user's playlist can also be named "Playlists"; do not use these title matches for playback.
    private static final Set<String> playlistsTitleMatchMediaIds = ConcurrentHashMap.newKeySet();
    // Give each Library load a number to distinguish earlier requests from later requests.
    private static final AtomicLong playlistsLoadCounter = new AtomicLong();
    // Remember which Library load last updated each folder on each Android Auto connection.

    @GuardedBy("itself")
    private static final WeakHashMap<Object, Map<String, PlaylistsFolderDelivery>>
            playlistsFolderDeliveries = new WeakHashMap<>();
    // Saved request for updates to Library or one of its folders.
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
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
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

    // Intercept Android Auto browse requests

    /**
     * Injection point. YTM has already called detach() on the result, so it can be sent after this method returns.
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
            if (requestedMediaId.equals(SPEED_DIAL_MEDIA_ID)) {
                HomeCollectionLoad load = new HomeCollectionLoad(androidAutoRequest, SPEED_DIAL_BROWSE_ID, null);
                refreshHandler.postDelayed(load.timeout, DEADLINE_MS);
                BACKGROUND_EXECUTOR.execute(load::start);
                return true;
            }
            if (requestedMediaId.startsWith(HOME_SECTION_PREFIX)) {
                // These page IDs store the shelf heading; reread Home to find its current contents.
                String section = Uri.decode(requestedMediaId.substring(HOME_SECTION_PREFIX.length()));
                HomeCollectionLoad load = new HomeCollectionLoad(androidAutoRequest, "FEmusic_home", section);
                refreshHandler.postDelayed(load.timeout, DEADLINE_MS);
                BACKGROUND_EXECUTOR.execute(load::start);
                return true;
            }
            if (requestedMediaId.equals(androidAutoLibraryMediaId)) {
                PreparedItems folders = new PreparedItems();
                for (LibraryFolder folder : LibraryFolder.values()) {
                    // Native Auto places uploads inside the corresponding Albums and Songs categories.
                    if (folder == LibraryFolder.UPLOADED_ALBUMS || folder == LibraryFolder.UPLOADED_SONGS) continue;
                    folders.add(folder.mediaItem());
                }
                androidAutoRequest.patch_deliverAndroidAutoItems(folders);
                return true;
            }
            if (requestedMediaId.startsWith(PODCAST_COLLECTION_PREFIX)
                    || requestedMediaId.startsWith(LIBRARY_PREFIX)
                    && !requestedMediaId.equals(LibraryFolder.PLAYLISTS.mediaId())) {
                CollectionLoad load = new CollectionLoad(androidAutoRequest);
                refreshHandler.postDelayed(load.timeout, DEADLINE_MS);
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
                PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR)
                : load.phoneBrowseClient.patch_requestLibraryPagination(
                paginationCommand, BACKGROUND_EXECUTOR);
        libraryResponseFuture.addListener(() -> {
            if (load.isDeliveryPreparationStarted()) return;

            try {
                PhoneBrowseResponse libraryResponse = libraryResponseFuture.get();
                Object nextPaginationCommand = firstPage
                        ? appendInitialLibraryPlaylists(libraryResponse, load)
                        : appendPaginatedLibraryPlaylists(libraryResponse, load);

                // The timeout may have started returning playlists while this Library response was being read.
                if (load.isDeliveryPreparationStarted()) return;

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

        Logger.printDebug(() -> "Found playlists in phone Library: " + load.getPlaylistCount());
        return paginationCommand;
    }

    private static Object appendPaginatedLibraryPlaylists(PhoneBrowseResponse libraryResponse,
                                                          PlaylistsFolderLoad load) {
        GridRenderer gridRenderer = libraryResponse.patch_getPaginatedLibraryGrid();
        // An unrecognized pagination result ends loading; keep the playlists collected so far.
        if (gridRenderer == null) return null;
        collectPlaylistsFromGrid(gridRenderer, load);
        Logger.printDebug(() -> "Found playlists in phone Library: " + load.getPlaylistCount());
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
        load.addPlaylistIfNew(playlistBrowseId, playlist);
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

    /** Ordered playlist commands for podcasts and uploaded music. */
    static byte[] orderedPlaylistCommand(String playlistId, boolean uploaded) {
        if (playlistId.isEmpty()) return new byte[0];
        // Auto's ordered WatchPlaylist action distinguishes catalog playlists from uploads.
        byte[] playbackOptions = {0x10, 0x02};
        byte[] collectionOptions = {0x10, (byte) (uploaded ? 1 : 2)};
        return message(AndroidAutoHomeResponseParser.WATCH_PLAYLIST,
                message(1, playlistId.getBytes(StandardCharsets.UTF_8)),
                message(7, playbackOptions),
                message(96_593_618, message(96_593_634, message(2, collectionOptions))));
    }

    private static byte[] message(int field, byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int size = 0;
        for (byte[] value : values) size += value.length;
        writeVarint(output, (field << 3) | 2);
        writeVarint(output, size);
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    // Refresh after Library changes

    public static synchronized void rememberAndroidAutoSubscription(AndroidAutoFolderReload browserService,
                                                                    @Nullable String parentMediaId,
                                                                    Object connection) {
        try {
            if (parentMediaId == null) return;
            if (parentMediaId.startsWith(LIBRARY_PREFIX)
                    || parentMediaId.startsWith(PODCAST_COLLECTION_PREFIX)
                    || parentMediaId.startsWith(HOME_SECTION_PREFIX)
                    || SPEED_DIAL_MEDIA_ID.equals(parentMediaId)
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
                case "subscription/subscribe":
                case "subscription/unsubscribe":
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
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
        refreshHandler.postDelayed(REFRESH_LIBRARY, LIBRARY_REFRESH_DELAY_MILLISECONDS);
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

    /** Injection point. Recognizes page IDs handled by the patch instead of YTM's decoder. */
    public static boolean isCustomAndroidAutoPageId(@Nullable String mediaId) {
        return PODCASTS_MEDIA_ID.equals(mediaId)
                || SPEED_DIAL_MEDIA_ID.equals(mediaId)
                || mediaId != null && (mediaId.startsWith(LIBRARY_PREFIX)
                || mediaId.startsWith(HOME_SECTION_PREFIX) || mediaId.startsWith(PODCAST_COLLECTION_PREFIX));
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
                // Native Home also includes these folders alongside music recommendations.
                return new ArrayList<>(cachedAndroidAutoPodcastFolders);
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
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
        androidAutoHomeMediaId = null;
        androidAutoLibraryMediaId = null;
        cachedAndroidAutoPodcastFolders = Collections.emptyList();

        // Add Podcasts only when YTM supplies Home and Library without a Podcasts tab.
        if (rootTabs == null || rootTabs.size() != 2) return rootTabs;

        androidAutoHomeMediaId = rootTabs.get(0).a();
        androidAutoLibraryMediaId = rootTabs.get(1).a();
        List<MediaBrowserCompat.MediaItem> updatedRootTabs = new ArrayList<>(rootTabs);
        Bundle podcastsExtras = new Bundle();
        podcastsExtras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
        podcastsExtras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
        podcastsExtras.putBoolean("com.google.android.apps.youtube.music.mediabrowser.IS_RECOMMENDED_PODCASTS", true);
        MediaDescriptionCompat podcastsDescription = new MediaDescriptionCompat(
                PODCASTS_MEDIA_ID,
                ResourceUtils.getString(PODCASTS_TITLE_RESOURCE_NAME),
                null, null, null, Uri.parse(LibraryFolder.PODCASTS.artworkUri()), podcastsExtras, null);
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
        refreshHandler.post(() -> {
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

    /** Injection point. Loads phone Home before the native browse request completes. */
    public static boolean loadAndroidAutoHome(AndroidAutoBrowseRequest request,
                                               @Nullable List<MediaBrowserCompat.MediaItem> nativeItems) {
        if (phoneBrowseClient == null
                || nativeItems == null || !Objects.equals(request.patch_getRequestedMediaId(), androidAutoHomeMediaId)
                || androidAutoHomeMediaId == null) return false;
        // Sending Home results calls this method again; skip our completed list to avoid another load.
        if (nativeItems instanceof PreparedItems) return false;
        HomeLoad load = new HomeLoad(request, nativeItems);
        refreshHandler.postDelayed(load.timeout, DEADLINE_MS);
        BACKGROUND_EXECUTOR.execute(load::start);
        return true;
    }

    /** Home shelves span multiple phone responses; opening a shelf must follow the same continuation. */
    private abstract static class HomeFeedLoad extends Load {
        private final String browseId;
        @GuardedBy("this")
        private final List<AndroidAutoHomeResponseParser.Item> collected = new ArrayList<>();
        private final Set<String> continuations = new HashSet<>();
        private volatile Requests<RequestKey, AndroidAutoHomeResponseParser.HomePage>.Lease pageLease;

        HomeFeedLoad(String browseId) {
            super(phoneBrowseClient);
            this.browseId = browseId;
        }

        final void start() { requestPage(""); }

        private void requestPage(String continuation) {
            if (finished.get()) return;
            try {
                if (!current(scope)) { finish(Collections.emptyList()); return; }
                PhoneBrowseClient client = (PhoneBrowseClient) scope.client();
                String key = continuation.isEmpty() ? browseId : "continuation:" + continuation;
                var work = HOME_PAGES.acquire(scope.account() == null ? null : new RequestKey(key, scope),
                        () -> transformFuture(continuation.isEmpty()
                                        ? client.patch_requestBrowse(browseId, BACKGROUND_EXECUTOR)
                                        : client.patch_requestBrowseContinuation(continuation, BACKGROUND_EXECUTOR),
                                response -> AndroidAutoHomeResponseParser.homePage(response.patch_responseBytes()),
                                BACKGROUND_EXECUTOR));
                pageLease = work;
                if (finished.get()) { work.close(); return; }
                work.future.whenCompleteAsync((page, error) -> {
                    work.close();
                    if (finished.get()) return;
                    if (error != null) {
                        Logger.printException(() -> "Could not load Android Auto Home page", error);
                        finishCollected();
                        return;
                    }
                    synchronized (this) { collected.addAll(page.items()); }
                    if (page.continuation().isEmpty() || !continuations.add(page.continuation())) {
                        finishCollected();
                    } else {
                        requestPage(page.continuation());
                    }
                }, BACKGROUND_EXECUTOR);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not request Android Auto Home page", ex);
                finishCollected();
            }
        }

        private void finishCollected() {
            if (finished.get()) return;
            try {
                List<AndroidAutoHomeResponseParser.Item> snapshot;
                synchronized (this) { snapshot = new ArrayList<>(collected); }
                finish(convert(snapshot));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not convert Android Auto Home items", ex);
                finish(Collections.emptyList());
            }
        }

        // A slow or failed continuation must not discard shelves already received.
        @Override void timedOut() { finishCollected(); }

        @Override void cleanup() {
            var work = pageLease;
            if (work != null) work.close();
        }

        abstract List<MediaBrowserCompat.MediaItem> convert(List<AndroidAutoHomeResponseParser.Item> items);
    }

    private static final class HomeLoad extends HomeFeedLoad {
        final AndroidAutoBrowseRequest request;
        final List<MediaBrowserCompat.MediaItem> podcastFolders;

        HomeLoad(AndroidAutoBrowseRequest request, List<MediaBrowserCompat.MediaItem> podcastFolders) {
            super("FEmusic_home");
            this.request = request;
            this.podcastFolders = new ArrayList<>(podcastFolders);
        }

        @Override List<MediaBrowserCompat.MediaItem> convert(List<AndroidAutoHomeResponseParser.Item> items) {
            return homeMediaItems(items);
        }

        @Override void deliver(List<MediaBrowserCompat.MediaItem> items) {
            PreparedItems home = new PreparedItems();
            if (current(scope)) {
                home.addAll(items);
                // Separate feeds provide no shared ranking; retain each feed's order.
                home.addAll(podcastFolders);
            }
            request.patch_deliverAndroidAutoItems(home);
        }
    }

    private static PreparedItems homeMediaItems(List<AndroidAutoHomeResponseParser.Item> results) {
        PreparedItems items = new PreparedItems(2);
        Map<String, List<AndroidAutoHomeResponseParser.Item>> sections = new LinkedHashMap<>();
        for (var item : results) sections.computeIfAbsent(item.group(), ignored -> new ArrayList<>()).add(item);
        for (var section : sections.entrySet()) {
            String title = section.getKey();
            List<AndroidAutoHomeResponseParser.Item> rows = section.getValue();
            if (title.isEmpty()) {
                items.addAll(mediaItems(rows, 2, false));
                continue;
            }
            boolean speedDial = SPEED_DIAL_BROWSE_ID.equals(rows.get(0).groupBrowseId());
            String mediaId = speedDial ? SPEED_DIAL_MEDIA_ID : HOME_SECTION_PREFIX + Uri.encode(title);
            Bundle extras = new Bundle();
            extras.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", 1);
            extras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
            extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2);
            items.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat(
                    mediaId, title, null, null, null, null, extras, null),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            // Native Home previews three Speed dial entries below its collection header.
            if (speedDial) {
                var preview = mediaItems(rows, 2, false);
                items.addAll(preview.subList(0, Math.min(3, preview.size())));
            }
        }
        return items;
    }

    /** Reloads the selected Home collection so returning to it reflects YTM's current recommendations. */
    private static final class HomeCollectionLoad extends HomeFeedLoad {
        final AndroidAutoBrowseRequest request;
        @Nullable final String section;

        HomeCollectionLoad(AndroidAutoBrowseRequest request, String browseId, @Nullable String section) {
            super(browseId);
            this.request = request;
            this.section = section;
        }

        @Override List<MediaBrowserCompat.MediaItem> convert(List<AndroidAutoHomeResponseParser.Item> items) {
            List<AndroidAutoHomeResponseParser.Item> rows = new ArrayList<>();
            for (var item : items) {
                if (section == null || section.equals(item.group())) rows.add(item);
            }
            return mediaItems(rows, 2, false);
        }

        @Override void deliver(List<MediaBrowserCompat.MediaItem> items) {
            request.patch_deliverAndroidAutoItems(items);
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
                if (!current(scope)) {
                    finish(Collections.emptyList()); return;
                }
                boolean podcastPage = id.startsWith(PODCAST_COLLECTION_PREFIX);
                LibraryFolder folder = podcastPage ? null : LibraryFolder.valueOf(id.substring(LIBRARY_PREFIX.length()));
                String browseId = podcastPage ? Uri.decode(id.substring(PODCAST_COLLECTION_PREFIX.length())) : folder.browseId;
                PhoneBrowseClient client = (PhoneBrowseClient) scope.client();
                await(REQUESTS.acquire(scope.account() == null ? null : new RequestKey(id, scope),
                        () -> transformFuture(client.patch_requestBrowse(browseId, BACKGROUND_EXECUTOR),
                                response -> {
                                    var items = AndroidAutoHomeResponseParser.collection(response.patch_responseBytes());
                                    return podcastPage ? mediaItems(items, 1) : libraryMediaItems(folder, items);
                                }, BACKGROUND_EXECUTOR)));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load Android Auto Library collection", ex);
                finish(Collections.emptyList());
            }
        }

        @Override void deliver(List<MediaBrowserCompat.MediaItem> items) {
            request.patch_deliverAndroidAutoItems(items);
        }
    }

    private static PreparedItems libraryMediaItems(LibraryFolder folder,
                                                   List<AndroidAutoHomeResponseParser.Item> results) {
        if (folder == LibraryFolder.PODCASTS) return podcastMediaItems(results);
        PreparedItems items = new PreparedItems(folder == LibraryFolder.LAST_PLAYED ? 1 : 2);
        if (folder == LibraryFolder.ALBUMS || folder == LibraryFolder.SONGS) {
            LibraryFolder uploads = folder == LibraryFolder.ALBUMS
                    ? LibraryFolder.UPLOADED_ALBUMS : LibraryFolder.UPLOADED_SONGS;
            items.add(uploads.mediaItem());
        }
        items.addAll(mediaItems(results, items.itemStyle));
        return items;
    }

    static final long DEADLINE_MS = 8_000;
    private static final Requests<RequestKey, List<MediaBrowserCompat.MediaItem>> REQUESTS = new Requests<>();
    private static final Requests<RequestKey, AndroidAutoHomeResponseParser.HomePage> HOME_PAGES = new Requests<>();
    private static final int MAX_COMMAND_BYTES = 16 * 1024;
    static final int MAX_NAVIGATION_LENGTH = 24 * 1024;

    // Media items, artwork, and playback commands

    static final class PreparedItems extends ArrayList<MediaBrowserCompat.MediaItem> {
        final int itemStyle;

        PreparedItems() { this(1); }
        PreparedItems(int itemStyle) { this.itemStyle = itemStyle; }

        void add(String id, String title, String subtitle, String artwork, boolean playable) {
            add(id, title, subtitle, artwork, playable, "");
        }

        void add(String id, String title, String subtitle, String artwork, boolean playable, String group) {
            if (id == null || id.length() > MAX_NAVIGATION_LENGTH) return;
            Bundle extras = new Bundle();
            // Native collection headers are rows even when their playable children use a grid.
            extras.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT", playable ? itemStyle : 1);
            if (!group.isEmpty()) extras.putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", group);
            add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat(
                    id, title, subtitle, null, null, artwork.isEmpty() ? null : Uri.parse(artwork), extras, null),
                    playable ? MediaBrowserCompat.MediaItem.FLAG_PLAYABLE : MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }
    }

    private static PreparedItems podcastMediaItems(List<AndroidAutoHomeResponseParser.Item> results) {
        PreparedItems items = new PreparedItems(1);
        Set<String> seen = new HashSet<>();
        for (var row : results) {
            String browseId = new AndroidAutoProtoMessage(row.command())
                    .messageField(AndroidAutoHomeResponseParser.BROWSE).stringField(2);
            if (browseId.isEmpty() || !seen.add(browseId)) continue;
            // Phone show IDs return empty pages through Auto's loader. Request their phone episode lists instead.
            items.add(PODCAST_COLLECTION_PREFIX + Uri.encode(browseId),
                    row.title(), row.subtitle(), row.artworkUrl(), false);
        }
        return items;
    }

    private static PreparedItems mediaItems(List<AndroidAutoHomeResponseParser.Item> results,
                                            int itemStyle) {
        return mediaItems(results, itemStyle, true);
    }

    private static PreparedItems mediaItems(List<AndroidAutoHomeResponseParser.Item> results,
                                            int itemStyle, boolean showGroups) {
        PreparedItems items = new PreparedItems(itemStyle);
        Set<String> seen = new HashSet<>();
        for (AndroidAutoHomeResponseParser.Item row : results) {
            byte[] command = row.playable() ? row.command() : row.playCommand();
            if (command.length > MAX_COMMAND_BYTES) continue;
            var endpoint = new AndroidAutoProtoMessage(command);
            if (!AndroidAutoHomeResponseParser.isPlayable(endpoint)) continue;
            String playlistId = endpoint.messageField(AndroidAutoHomeResponseParser.WATCH_PLAYLIST).stringField(1);
            // A menu's playlist action may shuffle; use ordered playback for albums and playlists.
            // Preserve YTM's "Start radio" action, which plays a generated mix.
            String browseId = new AndroidAutoProtoMessage(row.command())
                    .messageField(AndroidAutoHomeResponseParser.BROWSE).stringField(2);
            String id;
            if (browseId.startsWith("FEmusic_library_privately_owned_release_detail") && !playlistId.isEmpty()) {
                id = encodeCommand(orderedPlaylistCommand(playlistId, true));
            } else {
                id = !row.playable() && !playlistId.isEmpty() && !playlistId.startsWith("RD")
                        ? createPlaylistMediaId(playlistId) : encodeCommand(command);
            }
            if (id == null || !seen.add(id)) continue;
            items.add(id, row.title(), row.subtitle(), row.artworkUrl(), true, showGroups ? row.group() : "");
        }
        return items;
    }

    /** Replaced during patching with YTM's command parser and Android Auto media ID encoder. */
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

    // Avoid Futures.transform: YTM's bundled Guava can throw NoSuchMethodError.
    static <T, R> CompletableFuture<R> transformFuture(ListenableFuture<T> source,
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

    /** Each caller completes once and has its own timeout, even when requests are shared. */
    abstract static class Load {
        final Scope scope;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Requests<RequestKey, List<MediaBrowserCompat.MediaItem>>.Lease lease;
        final Runnable timeout = this::timedOut;

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
            refreshHandler.removeCallbacks(timeout);
            cleanup();
            var work = lease;
            if (work != null) {
                if (!work.future.isDone()) work.future.cancel(true);
                work.close();
            }
            refreshHandler.post(() -> {
                try { deliver(current(scope) ? items : Collections.emptyList()); }
                catch (Exception ex) { Logger.printException(() -> "Could not deliver Android Auto results", ex); }
            });
        }

        void timedOut() { finish(Collections.emptyList()); }
        void cleanup() {}
        abstract void deliver(List<MediaBrowserCompat.MediaItem> items);
    }

    /** Shares pending work only; completed results are never reused. */
    static final class Requests<K, V> {
        private final Map<K, Work> pending = new HashMap<>();

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
    // Search results and playback commands

    // Layout preference for one search result; Android Auto chooses the final layout.
    private static final String RESULT_LAYOUT_KEY = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT";
    // Vertical list with one result per row, instead of tiles arranged in a grid.
    private static final int VERTICAL_LIST_LAYOUT = 1;
    // Heading above a group of results, such as "Songs" or "Albums".
    private static final String CATEGORY_HEADING_KEY = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";

    static List<MediaBrowserCompat.MediaItem> createPlayableSearchResults(List<AndroidAutoResponseParser.SearchResult> results) {
        List<MediaBrowserCompat.MediaItem> playableResults = new ArrayList<>();
        Map<AndroidAutoResponseParser.ResultCategory, List<AndroidAutoResponseParser.SearchResult>> groups =
                new LinkedHashMap<>();
        // Keep YTM's section order; Auto needs each category's results together to show one heading.
        for (var result : results) groups.computeIfAbsent(result.category(), ignored -> new ArrayList<>()).add(result);
        List<AndroidAutoResponseParser.SearchResult> groupedResults = new ArrayList<>();
        for (var group : groups.values()) groupedResults.addAll(group);
        Set<String> seen = new HashSet<>();
        for (var searchResult : groupedResults) {
            byte[] playCommandBytes = searchResult.playCommandBytes();
            var playCommand = new AndroidAutoProtoMessage(playCommandBytes);
            if (!AndroidAutoHomeResponseParser.isPlayable(playCommand)) continue;
            String playlistId = playCommand.messageField(AndroidAutoResponseParser.WATCH_PLAYLIST).stringField(1);
            // A menu's playlist action may shuffle; use ordered playback for albums and playlists.
            // Preserve YTM's "Start radio" action, which plays a generated mix.
            String playbackMediaId = !searchResult.preservePlaybackCommand() && !playlistId.isEmpty() && !playlistId.startsWith("RD")
                    ? createPlaylistMediaId(playlistId) : encodeCommand(playCommandBytes);
            if (playbackMediaId == null || !seen.add(playbackMediaId)) continue;
            Bundle displayOptions = new Bundle();
            displayOptions.putInt(RESULT_LAYOUT_KEY, VERTICAL_LIST_LAYOUT);
            displayOptions.putString(CATEGORY_HEADING_KEY, ResourceUtils.getString(searchResult.category().titleResource));
            String artwork = searchResult.artworkUrl();
            playableResults.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat(
                    playbackMediaId, searchResult.title(), searchResult.subtitle(), null, null,
                    artwork.isEmpty() ? null : Uri.parse(artwork), displayOptions, null),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return playableResults;
    }

}
