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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Returns the playlists shown in YTM's phone Library when Android Auto opens Playlists.
 *
 * <p>The phone Library also contains artists, podcasts, New Episodes, and Episodes for Later, so
 * only playlists are kept. Its playlist rows already have browse IDs, titles, subtitles, and cover
 * artwork, but no Play action. Show them as playable rows after Library pagination, then request
 * only the selected playlist's Play action when Android Auto sends its media ID for playback.
 * Liked Music uses its first song because it has no Play button.
 *
 * <p>The Kotlin patch adds the interfaces and methods below to YTM's obfuscated classes.
 */
@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String PLAYLIST_BROWSE_MEDIA_ID_PREFIX = "morphe:aa:playlist:";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    // Bound a stalled Library request so Android Auto can leave the folder.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 120_000;
    // Bound a stalled selection without replacing current playback.
    private static final int ANDROID_AUTO_PLAYLIST_DETAILS_TIMEOUT_MILLISECONDS = 30_000;
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    // A user playlist can also be named Playlists. The title match only affects opening
    // folders; it does not change playlist playback.
    private static final Set<String> PLAYLISTS_TITLE_MATCH_MEDIA_IDS =
            ConcurrentHashMap.newKeySet();
    private static final AtomicLong PLAYLIST_SELECTION = new AtomicLong();

    // YTM uses this object to request the phone Library, more playlists, and opened playlists.
    public interface PhoneBrowseRequests {
        @NonNull ListenableFuture<BrowseResponse> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<BrowseResponse> patch_requestMorePlaylists(
                @NonNull Object continuationAction, @NonNull Executor executor);
    }

    // Library, pagination, and opened-playlist requests all return this class.
    public interface BrowseResponse {
        // Contents of the first Library page or an opened playlist.
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

    // GridRenderer holds the Library's items and actions for loading more items.
    public interface GridRenderer {
        // The phone Library grid mixes playlists with artists, podcasts, and other content.
        @NonNull Iterable<?> patch_getRows();
        // NEXT loads more items; RELOAD refreshes the list.
        @NonNull Iterable<?> patch_getContinuationActions();
    }

    // Protobuf field 175617300 contains the songs below an opened playlist's header.
    public interface OpenedPlaylistSongs {
        @NonNull Iterable<PlaylistOrTrack> patch_getSongs();
    }

    // Carries a requested Android Auto folder ID and its children.
    public interface AndroidAutoPlaylistsRequest {
        @Nullable String patch_getRequestedMediaId();
        void patch_deliverAndroidAutoPlaylists(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoPlaylists);
    }

    // The replay must run on the Looper registered with YTM's media session callback.
    public interface PlaybackCallback {
        @Nullable Handler patch_getCallbackHandler();
    }

    // YTM uses protobuf field 161429595 for a Library playlist or an opened-playlist song.
    public interface PlaylistOrTrack {
        @Nullable String patch_getPlaylistBrowseId();
        @Nullable String patch_getPlayableMediaId();
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

    /** Injection point. Captures the YTM object used for phone Library and playlist requests. */
    public static void setPhoneBrowseRequests(@NonNull PhoneBrowseRequests requests) {
        phoneBrowseRequests = requests;
        Logger.printDebug(() -> "Ready to request phone Library and opened playlists: " +
                requests.getClass().getName());
    }

    /** Injection point. Records media IDs whose title matches Android Auto's localized Playlists. */
    public static void rememberPlaylistsTitleMatch(
            @Nullable String androidAutoMediaId, @Nullable CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE_NAME)
                .contentEquals(title)) return;
        if (androidAutoMediaId != null) PLAYLISTS_TITLE_MATCH_MEDIA_IDS.add(androidAutoMediaId);
    }

    /**
     * Injection point. YTM detaches MediaBrowserService.Result before this hook, so the Android
     * Auto playlist list can be delivered after the phone request finishes.
     */
    public static boolean handleAndroidAutoPlaylists(
            @NonNull AndroidAutoPlaylistsRequest androidAutoRequest) {
        try {
            PhoneBrowseRequests phoneRequests = phoneBrowseRequests;
            if (phoneRequests == null) return false;
            String requestedMediaId = androidAutoRequest.patch_getRequestedMediaId();
            if (requestedMediaId == null) return false;
            if (!PLAYLISTS_TITLE_MATCH_MEDIA_IDS.contains(requestedMediaId)) return false;
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

    /** Injection point. Resolve a selected playlist before YTM decodes its playable media ID. */
    public static boolean handlePlayFromMediaId(
            @NonNull MediaSession.Callback callback, @Nullable String mediaId,
            @Nullable Bundle extras) {
        if (mediaId == null || !mediaId.startsWith(PLAYLIST_BROWSE_MEDIA_ID_PREFIX)) {
            PLAYLIST_SELECTION.incrementAndGet();
            return false;
        }
        long selection = PLAYLIST_SELECTION.incrementAndGet();
        PlaybackCallback playbackCallback = (PlaybackCallback) callback;
        String playlistBrowseId = mediaId.substring(PLAYLIST_BROWSE_MEDIA_ID_PREFIX.length());
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
        AtomicBoolean completed = new AtomicBoolean();
        Utils.runOnMainThreadDelayed(() -> {
            if (completed.compareAndSet(false, true) && selection == PLAYLIST_SELECTION.get()) {
                Logger.printDebug(() -> "Selected Android Auto playlist request timed out");
            }
        }, ANDROID_AUTO_PLAYLIST_DETAILS_TIMEOUT_MILLISECONDS);
        try {
            ListenableFuture<BrowseResponse> future =
                    requests.patch_requestBrowse(playlistBrowseId, BACKGROUND_EXECUTOR);
            future.addListener(() -> {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    BrowseResponse response = future.get();
                    // An "Add a song" action has a media ID, but cannot start playback.
                    PlaylistOrTrack firstSong = findFirstPlayableSong(response);
                    String playableMediaId = firstSong == null ? null
                            : LIKED_MUSIC_BROWSE_ID.equals(playlistBrowseId)
                                    ? firstSong.patch_getPlayableMediaId()
                                    : response.patch_getPlayableMediaId();
                    if (playableMediaId == null) {
                        Logger.printDebug(() -> "Selected Android Auto playlist is empty");
                        return;
                    }
                    callbackHandler.post(() -> {
                        // A slower earlier selection must not replace the user's latest choice.
                        if (selection != PLAYLIST_SELECTION.get() || requests != phoneBrowseRequests)
                            return;
                        callback.onPlayFromMediaId(playableMediaId, playbackExtras);
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
                // Waiting for every opened page here made the whole folder wait for the slowest one.
                deliverPhonePlaylists(androidAutoRequest, state);
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

    private static void deliverPhonePlaylists(
            AndroidAutoPlaylistsRequest androidAutoRequest, PhonePlaylistsState state) {
        List<PhonePlaylist> phonePlaylists = state.phonePlaylists;
        MediaBrowserCompat.MediaItem[] androidAutoPlaylists =
                new MediaBrowserCompat.MediaItem[phonePlaylists.size()];
        for (int index = 0; index < phonePlaylists.size(); index++) {
            PhonePlaylist playlist = phonePlaylists.get(index);
            androidAutoPlaylists[index] = createAndroidAutoLazyPlaylist(
                    playlist.playlistBrowseId, playlist.title, playlist.subtitle,
                    playlist.artworkUri);
        }
        synchronized (state) {
            state.androidAutoPlaylists = androidAutoPlaylists;
        }
        deliverAndroidAutoPlaylists(androidAutoRequest, state);
    }

    private static PlaylistOrTrack findFirstPlayableSong(BrowseResponse playlistResponse) {
        for (BrowseTab tab : playlistResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof OpenedPlaylistSongs)) continue;
                for (PlaylistOrTrack song :
                        ((OpenedPlaylistSongs) sectionContent).patch_getSongs()) {
                    if (!song.patch_hasPlayableVideoId()) continue;
                    String playableMediaId = song.patch_getPlayableMediaId();
                    if (playableMediaId != null) return song;
                }
            }
        }
        return null;
    }

    private static MediaBrowserCompat.MediaItem createAndroidAutoLazyPlaylist(
            String playlistBrowseId, String title, String subtitle, Uri artworkUri) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                PLAYLIST_BROWSE_MEDIA_ID_PREFIX + playlistBrowseId,
                title, subtitle, null, null, artworkUri, null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static void deliverAndroidAutoPlaylists(
            AndroidAutoPlaylistsRequest androidAutoRequest,
            PhonePlaylistsState state) {
        List<MediaBrowserCompat.MediaItem> androidAutoPlaylistItems;
        synchronized (state) {
            if (state.androidAutoResultDelivered) return;
            state.androidAutoResultDelivered = true;
            androidAutoPlaylistItems = new ArrayList<>(state.androidAutoPlaylists.length);
            for (MediaBrowserCompat.MediaItem androidAutoPlaylist : state.androidAutoPlaylists) {
                if (androidAutoPlaylist != null) {
                    androidAutoPlaylistItems.add(androidAutoPlaylist);
                }
            }
        }
        try {
            androidAutoRequest.patch_deliverAndroidAutoPlaylists(androidAutoPlaylistItems);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
        }
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
