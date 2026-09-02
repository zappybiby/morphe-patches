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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Returns the playlists shown in YTM's phone Library when Android Auto opens Playlists.
 *
 * <p>The phone Library also contains artists, podcasts, New Episodes, and Episodes for Later, so
 * only playlists are kept. Each playlist's Play button supplies the media ID Android Auto uses to
 * start playback. Liked Music has no Play button, so its first playable song starts the queue.
 *
 * <p>The Kotlin patch adds the interfaces and methods below to YTM's obfuscated classes.
 */
@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    // Resolving 33 playlists took over one minute; stalled Browse futures remained pending for two minutes.
    // Return the resolved playlists after two minutes instead of retrying the requests.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 120_000;
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    // A playlist named Playlists starts playback; Android Auto's Playlists folder opens the list.
    private static final Set<String> PLAYLISTS_TITLE_MATCH_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();

    // YTM uses this object to request the phone Library, more playlists, and opened playlists.
    public interface PhoneBrowseRequests {
        @NonNull ListenableFuture<BrowseResponse> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<BrowseResponse> patch_requestMorePlaylists(
                @NonNull Object continuationAction, @NonNull Executor executor);
    }

    // Library, pagination, and opened-playlist requests all return this class.
    public interface BrowseResponse {
        // Initial Library and opened-playlist responses.
        @NonNull Iterable<BrowseTab> patch_getTabs();
        // Pagination responses.
        @Nullable GridRenderer patch_getMorePlaylists();
        // Opened-playlist responses.
        @Nullable String patch_getPlayableMediaId();
    }

    // YTM uses TabRenderer for both Library and opened-playlist contents, even when no tab is visible.
    public interface BrowseTab {
        @Nullable SectionList patch_getSectionList();
    }

    // SectionListRenderer is an invisible container for the Library grid or opened-playlist songs.
    public interface SectionList {
        @NonNull Iterable<?> patch_getContents();
    }

    public interface GridRenderer {
        // The phone Library grid mixes playlists with artists, podcasts, and other content.
        @NonNull Iterable<?> patch_getRows();
        // NEXT requests another batch; RELOAD replaces the current grid.
        @NonNull Iterable<?> patch_getContinuationActions();
    }

    // Protobuf field 175617300 contains the songs below an opened playlist's header.
    public interface OpenedPlaylistSongs {
        @NonNull Iterable<PlaylistOrTrack> patch_getSongs();
    }

    // Carries the requested Playlists folder ID and the playlist list returned to Android Auto.
    public interface AndroidAutoPlaylistsRequest {
        @Nullable String patch_getRequestedMediaId();
        void patch_deliverAndroidAutoPlaylists(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoPlaylists);
    }

    // YTM uses protobuf field 161429595 for a Library playlist or an opened-playlist song.
    public interface PlaylistOrTrack {
        @Nullable String patch_getPlaylistBrowseId();
        @Nullable String patch_getPlayableMediaId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    // MusicBrowserService recreation replaces this; active loads keep the instance in their state.
    @Nullable
    private static volatile PhoneBrowseRequests phoneBrowseRequests;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /** Injection point. Captures the YTM object used for phone Library and playlist requests. */
    public static void setPhoneBrowseRequests(@NonNull PhoneBrowseRequests requests) {
        phoneBrowseRequests = requests;
        Logger.printDebug(() -> "Ready to request phone Library and opened playlists: " +
                requests.getClass().getName());
    }

    /**
     * Injection point. YTM detaches MediaBrowserService.Result before this hook, so the Android
     * Auto playlist list can be delivered after the phone requests finish.
     */
    public static boolean handleAndroidAutoPlaylists(
            @NonNull AndroidAutoPlaylistsRequest androidAutoRequest) {
        try {
            PhoneBrowseRequests phoneRequests = phoneBrowseRequests;
            if (phoneRequests == null) return false;
            if (!isAndroidAutoPlaylistsRequest(androidAutoRequest)) return false;
            PhonePlaylistsState state = new PhonePlaylistsState(phoneRequests);
            Utils.runOnMainThreadDelayed(
                    () -> deliverAndroidAutoPlaylists(androidAutoRequest, state),
                    ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS);
            requestPhoneLibrary(androidAutoRequest, state);
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto Playlists request", ex);
            return false;
        }
    }

    /** Injection point. Records media IDs whose title matches Android Auto's localized Playlists. */
    public static void rememberPlaylistsTitleMatch(
            @Nullable String androidAutoMediaId, @Nullable CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE_NAME)
                .contentEquals(title)) return;
        if (androidAutoMediaId != null) PLAYLISTS_TITLE_MATCH_MEDIA_IDS.add(androidAutoMediaId);
    }

    private static void requestPhoneLibrary(
            AndroidAutoPlaylistsRequest androidAutoRequest, PhonePlaylistsState state) {
        handleLibraryResponse(
                androidAutoRequest, state,
                state.phoneBrowseRequests.patch_requestBrowse(
                        PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR),
                RestoreAndroidAutoPlaylistsPatch::collectLibraryPlaylists);
    }

    private static void requestMorePlaylists(
            AndroidAutoPlaylistsRequest androidAutoRequest, PhonePlaylistsState state,
            Object continuationAction) {
        handleLibraryResponse(
                androidAutoRequest, state,
                state.phoneBrowseRequests.patch_requestMorePlaylists(
                        continuationAction, BACKGROUND_EXECUTOR),
                RestoreAndroidAutoPlaylistsPatch::collectMorePlaylists);
    }

    private static void handleLibraryResponse(
            AndroidAutoPlaylistsRequest androidAutoRequest, PhonePlaylistsState state,
            ListenableFuture<BrowseResponse> libraryResponseFuture,
            BiFunction<BrowseResponse, PhonePlaylistsState, Object>
                    collectPlaylistsAndGetContinuation) {
        libraryResponseFuture.addListener(() -> {
            try {
                BrowseResponse libraryResponse = libraryResponseFuture.get();
                Object continuationAction =
                        collectPlaylistsAndGetContinuation.apply(libraryResponse, state);
                if (continuationAction != null) {
                    requestMorePlaylists(androidAutoRequest, state, continuationAction);
                    return;
                }
                requestEachPlaylist(androidAutoRequest, state);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM Library request interrupted", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, state);
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM Library request failed", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, state);
            }
        }, BACKGROUND_EXECUTOR);
    }

    private static void deliverAndroidAutoPlaylists(
            AndroidAutoPlaylistsRequest androidAutoRequest,
            PhonePlaylistsState state) {
        List<MediaBrowserCompat.MediaItem> playableAndroidAutoPlaylists;
        synchronized (state) {
            if (state.androidAutoResultDelivered) return;
            state.androidAutoResultDelivered = true;
            playableAndroidAutoPlaylists = new ArrayList<>(state.androidAutoPlaylists.length);
            for (MediaBrowserCompat.MediaItem androidAutoPlaylist : state.androidAutoPlaylists) {
                if (androidAutoPlaylist != null) {
                    playableAndroidAutoPlaylists.add(androidAutoPlaylist);
                }
            }
        }
        try {
            androidAutoRequest.patch_deliverAndroidAutoPlaylists(playableAndroidAutoPlaylists);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
        }
    }

    private static Object collectLibraryPlaylists(
            BrowseResponse libraryResponse, PhonePlaylistsState state) {
        Object continuationAction = null;
        for (BrowseTab tab : libraryResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof GridRenderer)) continue;
                GridRenderer gridRenderer = (GridRenderer) sectionContent;
                collectPlaylistsFromGrid(gridRenderer, state);
                if (continuationAction == null) {
                    continuationAction = firstContinuationAction(gridRenderer);
                }
            }
        }
        Logger.printDebug(() -> "Found playlists in phone Library: " + state.phonePlaylists.size());
        return continuationAction;
    }

    private static Object collectMorePlaylists(
            BrowseResponse libraryResponse, PhonePlaylistsState state) {
        GridRenderer gridRenderer = libraryResponse.patch_getMorePlaylists();
        if (gridRenderer == null) return null;
        collectPlaylistsFromGrid(gridRenderer, state);
        Logger.printDebug(() -> "Found playlists in phone Library: " + state.phonePlaylists.size());
        return firstContinuationAction(gridRenderer);
    }

    private static void collectPlaylistsFromGrid(
            GridRenderer gridRenderer, PhonePlaylistsState state) {
        for (Object gridRow : gridRenderer.patch_getRows()) {
            if (!(gridRow instanceof PlaylistOrTrack)) continue;
            try {
                addPhonePlaylist((PlaylistOrTrack) gridRow, state);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not read a Library grid row", ex);
            }
        }
    }

    @Nullable
    private static Object firstContinuationAction(GridRenderer gridRenderer) {
        Iterator<?> actions = gridRenderer.patch_getContinuationActions().iterator();
        return actions.hasNext() ? actions.next() : null;
    }

    private static void addPhonePlaylist(
            PlaylistOrTrack playlistOrTrack, PhonePlaylistsState state) {
        String playlistBrowseId = playlistOrTrack.patch_getPlaylistBrowseId();
        if (playlistBrowseId == null) return;
        // Episodes for Later (VLSE) has no Play button.
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(playlistBrowseId)) return;

        CharSequence titleText = playlistOrTrack.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        if (!state.seenPlaylistBrowseIds.add(playlistBrowseId)) return;
        // Subtitle and artwork are optional for playback.
        state.phonePlaylists.add(new PhonePlaylist(
                playlistBrowseId,
                title,
                subtitleOrEmpty(playlistOrTrack),
                artworkUriOrNull(playlistOrTrack)));
    }

    private static void requestEachPlaylist(
            AndroidAutoPlaylistsRequest androidAutoRequest, PhonePlaylistsState state) {
        List<PhonePlaylist> phonePlaylists = state.phonePlaylists;
        if (phonePlaylists.isEmpty()) {
            deliverAndroidAutoPlaylists(androidAutoRequest, state);
            return;
        }

        // YTM runs these Browse requests through its own executor and Cronet; no extra queue is
        // needed here. Keep Library order and omit failures or missing Play actions.
        MediaBrowserCompat.MediaItem[] androidAutoPlaylists =
                new MediaBrowserCompat.MediaItem[phonePlaylists.size()];
        synchronized (state) {
            state.androidAutoPlaylists = androidAutoPlaylists;
        }
        AtomicInteger remainingPlaylistRequests = new AtomicInteger(phonePlaylists.size());
        for (int index = 0; index < phonePlaylists.size(); index++) {
            int playlistIndex = index;
            PhonePlaylist phonePlaylist = phonePlaylists.get(index);
            try {
                ListenableFuture<BrowseResponse> playlistResponseFuture =
                        state.phoneBrowseRequests.patch_requestBrowse(
                                phonePlaylist.playlistBrowseId, BACKGROUND_EXECUTOR);
                playlistResponseFuture.addListener(() -> {
                    try {
                        BrowseResponse playlistResponse = playlistResponseFuture.get();
                        // Liked Music (VLLM) has no Play button; use its first playable song.
                        String playableMediaId = LIKED_MUSIC_BROWSE_ID.equals(
                                phonePlaylist.playlistBrowseId)
                                ? findFirstPlayableSongMediaId(playlistResponse)
                                : playlistResponse.patch_getPlayableMediaId();
                        if (playableMediaId != null) {
                            synchronized (state) {
                                androidAutoPlaylists[playlistIndex] = createAndroidAutoPlaylist(
                                        playableMediaId, phonePlaylist.title, phonePlaylist.subtitle,
                                        phonePlaylist.artworkUri);
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        Logger.printException(
                                () -> "YTM playlist request interrupted: "
                                        + phonePlaylist.playlistBrowseId,
                                ex);
                    } catch (ExecutionException | RuntimeException ex) {
                        Logger.printException(
                                () -> "YTM playlist request failed: "
                                        + phonePlaylist.playlistBrowseId,
                                ex);
                    } finally {
                        onPlaylistRequestFinished(
                                androidAutoRequest, state, remainingPlaylistRequests);
                    }
                }, BACKGROUND_EXECUTOR);
            } catch (RuntimeException ex) {
                Logger.printException(
                        () -> "Could not request YTM playlist: "
                                + phonePlaylist.playlistBrowseId,
                        ex);
                onPlaylistRequestFinished(
                        androidAutoRequest, state, remainingPlaylistRequests);
            }
        }
    }

    private static void onPlaylistRequestFinished(
            AndroidAutoPlaylistsRequest androidAutoRequest,
            PhonePlaylistsState state,
            AtomicInteger remainingPlaylistRequests) {
        if (remainingPlaylistRequests.decrementAndGet() != 0) return;
        deliverAndroidAutoPlaylists(androidAutoRequest, state);
    }

    private static String findFirstPlayableSongMediaId(BrowseResponse playlistResponse) {
        for (BrowseTab tab : playlistResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof OpenedPlaylistSongs)) continue;
                for (PlaylistOrTrack song :
                        ((OpenedPlaylistSongs) sectionContent).patch_getSongs()) {
                    String playableMediaId = song.patch_getPlayableMediaId();
                    if (playableMediaId != null) return playableMediaId;
                }
            }
        }
        return null;
    }

    private static String subtitleOrEmpty(PlaylistOrTrack playlist) {
        try {
            CharSequence subtitle = playlist.patch_getSubtitle();
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Uri artworkUriOrNull(PlaylistOrTrack playlist) {
        try {
            return playlist.patch_getArtworkUri();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MediaBrowserCompat.MediaItem createAndroidAutoPlaylist(
            String playableMediaId, String title, String subtitle, Uri artworkUri) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                playableMediaId, title, subtitle, null, null, artworkUri, null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static boolean isAndroidAutoPlaylistsRequest(
            AndroidAutoPlaylistsRequest androidAutoRequest) {
        String requestedMediaId = androidAutoRequest.patch_getRequestedMediaId();
        return requestedMediaId != null &&
                PLAYLISTS_TITLE_MATCH_MEDIA_IDS.contains(requestedMediaId);
    }

    private static final class PhonePlaylistsState {
        private final PhoneBrowseRequests phoneBrowseRequests;
        private final List<PhonePlaylist> phonePlaylists = new ArrayList<>();
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        private boolean androidAutoResultDelivered;
        private MediaBrowserCompat.MediaItem[] androidAutoPlaylists =
                new MediaBrowserCompat.MediaItem[0];

        private PhonePlaylistsState(PhoneBrowseRequests requests) {
            this.phoneBrowseRequests = requests;
        }
    }

    private static final class PhonePlaylist {
        private final String playlistBrowseId;
        private final String title;
        private final String subtitle;
        private final Uri artworkUri;

        private PhonePlaylist(
                String playlistBrowseId, String title, String subtitle, Uri artworkUri) {
            this.playlistBrowseId = playlistBrowseId;
            this.title = title;
            this.subtitle = subtitle;
            this.artworkUri = artworkUri;
        }
    }
}
