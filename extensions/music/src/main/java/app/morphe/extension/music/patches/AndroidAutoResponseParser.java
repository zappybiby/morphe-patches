/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads titles, artwork, and playback actions from phone search responses. */
final class AndroidAutoResponseParser {
    private static final int TABBED_SEARCH = 60_498_879;
    private static final int TAB = 58_174_010;
    private static final int SECTION_LIST = 49_399_797;
    private static final int ITEM_SECTION = 50_195_462;
    private static final int ELEMENT_RENDERER = 153_515_154;
    private static final int COMPONENT_RENDERER = 172_660_663;
    private static final int COMPONENT_DATA_MESSAGE = 168_777_401;
    private static final int SEARCH_RESULTS_MESSAGE = 410_090_561;
    private static final int TOP_RESULT = 1_223;
    private static final int COMPACT_RESULT_CARD = 1_297;
    private static final int INNERTUBE_COMMAND = 169_495_254;
    private static final int WATCH = 48_687_757;
    static final int WATCH_PLAYLIST = 52_666_186;
    private static final int BROWSE = 48_687_626;
    private static final int MUSIC_SHELF = 91_303_872;
    private static final int RESULT_TAP_COMMAND_FIELD = 4;
    private static final int TOP_RESULT_TAP_COMMAND_FIELD = 8;

    enum ResultCategory {
        TOP_RESULT("search_shelf_top_result_title"),
        SONGS("library_songs_shelf_title"),
        ALBUMS("library_albums_shelf_title"),
        ARTISTS("library_artists_shelf_title"),
        PLAYLISTS("library_playlists_shelf_title"),
        VIDEOS("morphe_music_android_auto_videos"),
        PODCASTS("offline_podcasts_shelf_title"),
        EPISODES("library_episodes_shelf_title"),
        UPLOADED_SONGS("morphe_music_android_auto_uploaded_songs"),
        UPLOADED_ALBUMS("morphe_music_android_auto_uploaded_albums"),
        UPLOADED_ARTISTS("morphe_music_android_auto_uploaded_artists"),
        OTHER("morphe_music_android_auto_other_results");

        final String titleResource;
        ResultCategory(String titleResource) { this.titleResource = titleResource; }
    }

    /**
     * {@code preservePlaybackCommand} retains direct-play, podcast, and uploaded-music command options.
     * Menu or Play-button commands may be replaced to start albums and playlists in order.
     */
    record SearchResult(String title, String subtitle, String artworkUrl, byte[] playCommandBytes,
                        boolean preservePlaybackCommand, ResultCategory category) {}
    static List<SearchResult> parseSearchResults(byte[] response, boolean uploaded) {
        if (response == null) throw new IllegalArgumentException("Missing search response");
        AndroidAutoProtoMessage responseMessage = new AndroidAutoProtoMessage(response);
        AndroidAutoProtoMessage contentsMessage = responseMessage.messageField(4);
        if (!contentsMessage.messageField(58_508_690).isEmpty()) return Collections.emptyList();
        AndroidAutoProtoMessage sectionsMessage = contentsMessage.messageField(SECTION_LIST);
        if (sectionsMessage.isEmpty()) {
            String tabId = uploaded ? "music_search_upload" : "music_search_catalog";
            boolean hasCatalogTab = false;
            boolean hasUploadTab = false;
            for (AndroidAutoProtoMessage tabMessage : contentsMessage.messageField(TABBED_SEARCH).repeatedMessageField(1)) {
                AndroidAutoProtoMessage tab = tabMessage.messageField(TAB);
                hasCatalogTab |= tab.stringField(11).equals("music_search_catalog");
                hasUploadTab |= tab.stringField(11).equals("music_search_upload");
                if (!tab.stringField(11).isEmpty() && !tab.stringField(11).equals(tabId)) continue;
                AndroidAutoProtoMessage contents = tab.messageField(4);
                if (!contents.messageField(58_508_690).isEmpty()) return Collections.emptyList();
                sectionsMessage = contents.messageField(SECTION_LIST);
                if (!sectionsMessage.isEmpty()) break;
            }
            // Accounts without uploads omit that tab from an otherwise valid search response.
            if (uploaded && hasCatalogTab && !hasUploadTab) return Collections.emptyList();
        }
        if (sectionsMessage.isEmpty()) throw new IllegalArgumentException("Unrecognized search contents");
        return readSections(sectionsMessage);
    }

    static String uploadedSearchParams(byte[] response) {
        for (AndroidAutoProtoMessage entry : new AndroidAutoProtoMessage(response).messagePath(4, TABBED_SEARCH).repeatedMessageField(1)) {
            AndroidAutoProtoMessage tab = entry.messageField(TAB);
            if (tab.stringField(11).equals("music_search_upload")) {
                return tab.messagePath(1, 48_687_709).stringField(2);
            }
        }
        return "";
    }

    private static List<SearchResult> readSections(AndroidAutoProtoMessage sectionsMessage) {
        List<SearchResult> results = new ArrayList<>();
        for (AndroidAutoProtoMessage sectionMessage : sectionsMessage.repeatedMessageField(1)) {
            AndroidAutoProtoMessage shelfMessage = sectionMessage.messageField(MUSIC_SHELF);
            if (!shelfMessage.isEmpty()) {
                readShelf(results, shelfMessage);
            }
            for (AndroidAutoProtoMessage containerData : sectionMessage.messageField(ITEM_SECTION).repeatedMessageField(1)) {
                AndroidAutoProtoMessage resultData = containerData.messagePath(ELEMENT_RENDERER, COMPONENT_RENDERER, 1,
                        COMPONENT_DATA_MESSAGE, 5);
                for (AndroidAutoProtoMessage searchResultMessage : resultData.messageField(SEARCH_RESULTS_MESSAGE)
                        .repeatedMessageField(1)) {
                    addSearchResult(results, searchResultMessage, RESULT_TAP_COMMAND_FIELD, false);
                }
                AndroidAutoProtoMessage topResult = resultData.messagePath(TOP_RESULT, 1);
                addSearchResult(results, topResult.messageField(5), TOP_RESULT_TAP_COMMAND_FIELD, true);
                for (AndroidAutoProtoMessage searchResultMessage : topResult.repeatedMessageField(7))
                    addSearchResult(results, searchResultMessage, RESULT_TAP_COMMAND_FIELD, false);
                // Compact cards also carry multi-row collections such as Listen again.
                // Only untitled cards are top results; named collections retain each row's category.
                AndroidAutoProtoMessage card = resultData.messagePath(COMPACT_RESULT_CARD, 1);
                boolean isTopResult = card.messageField(1).stringField(1).isEmpty();
                for (AndroidAutoProtoMessage row : card.repeatedMessageField(2))
                    addSearchResult(results, row, RESULT_TAP_COMMAND_FIELD, isTopResult);
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static void readShelf(List<SearchResult> results, AndroidAutoProtoMessage shelfMessage) {
        for (AndroidAutoProtoMessage shelfItemMessage : shelfMessage.repeatedMessageField(2)) {
            AndroidAutoProtoMessage searchResultMessage = shelfItemMessage.messageField(161_429_595);
            AndroidAutoProtoMessage tapCommand = searchResultMessage.messageField(5);
            boolean tapStartsPlayback = hasPlaybackAction(tapCommand);
            String title = textRuns(searchResultMessage.messageField(3));
            if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) continue;
            // If tapping opens a page on the phone, use its menu's play action.
            AndroidAutoProtoMessage menuMessage = searchResultMessage.messagePath(9, 66_439_850);
            AndroidAutoProtoMessage playCommand = tapStartsPlayback ? tapCommand : menuPlayCommand(menuMessage);
            ResultCategory category = resultCategory(tapCommand);
            if (!hasPlaybackAction(playCommand) && category == ResultCategory.EPISODES)
                playCommand = episodePlayCommand(menuMessage);
            boolean podcast = category == ResultCategory.PODCASTS;
            if (!hasPlaybackAction(playCommand) && podcast) playCommand = podcastPlayCommand(tapCommand);
            boolean uploadedCollection = category == ResultCategory.UPLOADED_ALBUMS
                    || category == ResultCategory.UPLOADED_ARTISTS;
            if (uploadedCollection) playCommand = playlistPlayCommand(
                    playCommand.messageField(WATCH_PLAYLIST).stringField(1), true);
            String subtitle = podcastSubtitle(menuMessage, category, textRuns(searchResultMessage.messageField(4)));
            results.add(new SearchResult(title, subtitle,
                    artwork(searchResultMessage.messagePath(1, 164_480_666, 1)),
                    playCommand.serializedBytes(), tapStartsPlayback || podcast || uploadedCollection, category));
        }
    }

    private static String textRuns(AndroidAutoProtoMessage textMessage) {
        StringBuilder value = new StringBuilder();
        for (AndroidAutoProtoMessage textRun : textMessage.repeatedMessageField(1)) value.append(textRun.stringField(1));
        return value.toString();
    }

    private static String artwork(AndroidAutoProtoMessage thumbnailsMessage) {
        String url = "";
        for (AndroidAutoProtoMessage thumbnailMessage : thumbnailsMessage.repeatedMessageField(1)) {
            String candidate = thumbnailMessage.stringField(1);
            if (candidate.startsWith("https://")) url = candidate;
        }
        return url;
    }

    private static void addSearchResult(List<SearchResult> results, AndroidAutoProtoMessage searchResultMessage,
                                        int tapCommandField, boolean isTopResult) {
        if (searchResultMessage.isEmpty()) return;
        boolean hasTopResultButtons = tapCommandField == TOP_RESULT_TAP_COMMAND_FIELD;
        String title = searchResultMessage.stringField(2);
        AndroidAutoProtoMessage tapCommand = searchResultMessage.messagePath(tapCommandField, INNERTUBE_COMMAND);
        boolean tapStartsPlayback = hasPlaybackAction(tapCommand);
        if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) return;
        String url = artwork(searchResultMessage.messagePath(1, 1));
        String subtitle = searchResultMessage.stringField(3);
        ResultCategory category = resultCategory(tapCommand);
        AndroidAutoProtoMessage playCommand = tapCommand;
        if (!tapStartsPlayback) {
            AndroidAutoProtoMessage menuMessage = searchResultMessage.messagePath(hasTopResultButtons ? 9 : 5,
                    INNERTUBE_COMMAND, 98_150_882, 1, 66_439_850);
            // Use the menu's play action without loading the result's phone page.
            playCommand = menuPlayCommand(menuMessage);
            // Top results expose their primary play action separately from the menu.
            if (hasTopResultButtons) {
                for (AndroidAutoProtoMessage buttonMessage : searchResultMessage.messageField(14).repeatedMessageField(1)) {
                    AndroidAutoProtoMessage buttonCommand = buttonMessage.messagePath(2, 6, INNERTUBE_COMMAND);
                    if (hasPlaybackAction(buttonCommand)) { playCommand = buttonCommand; break; }
                }
            }
            if (!hasPlaybackAction(playCommand) && category == ResultCategory.EPISODES)
                playCommand = episodePlayCommand(menuMessage);
            subtitle = podcastSubtitle(menuMessage, category, subtitle);
        }
        boolean podcast = category == ResultCategory.PODCASTS;
        if (!hasPlaybackAction(playCommand) && podcast) playCommand = podcastPlayCommand(tapCommand);
        boolean uploadedCollection = category == ResultCategory.UPLOADED_ALBUMS
                || category == ResultCategory.UPLOADED_ARTISTS;
        if (uploadedCollection) playCommand = playlistPlayCommand(
                playCommand.messageField(WATCH_PLAYLIST).stringField(1), true);
        results.add(new SearchResult(title, subtitle, url,
                playCommand.serializedBytes(), tapStartsPlayback || podcast || uploadedCollection,
                isTopResult ? ResultCategory.TOP_RESULT : category));
    }

    private static String podcastSubtitle(AndroidAutoProtoMessage menu, ResultCategory category, String subtitle) {
        if (category != ResultCategory.PODCASTS && category != ResultCategory.EPISODES) return subtitle;
        for (AndroidAutoProtoMessage entry : menu.repeatedMessageField(1)) {
            String creator = entry.messagePath(ELEMENT_RENDERER, COMPONENT_RENDERER, 1,
                    COMPONENT_DATA_MESSAGE, 5, 413_471_385, 1, 1_051, 3).stringField(1);
            if (!creator.isEmpty()) {
                return category == ResultCategory.PODCASTS || subtitle.isEmpty()
                        ? creator : subtitle + " • " + creator;
            }
        }
        return subtitle;
    }

    private static ResultCategory resultCategory(AndroidAutoProtoMessage tapCommand) {
        String browseId = tapCommand.messageField(BROWSE).stringField(2);
        if (browseId.startsWith("FEmusic_library_privately_owned_release_detail")) return ResultCategory.UPLOADED_ALBUMS;
        if (browseId.startsWith("FEmusic_library_privately_owned_artist")) return ResultCategory.UPLOADED_ARTISTS;
        if (browseId.startsWith("MPRE")) return ResultCategory.ALBUMS;
        if (browseId.startsWith("MPSP")) return ResultCategory.PODCASTS;
        if (browseId.startsWith("UC")) {
            // Artist and profile pages share the UC prefix; the page type distinguishes them.
            long pageType = tapCommand.messagePath(BROWSE, 6, 172_183_029).varintField(1);
            return pageType == 5 ? ResultCategory.OTHER : ResultCategory.ARTISTS;
        }
        if (browseId.startsWith("VL")) return ResultCategory.PLAYLISTS;
        if (browseId.startsWith("MPED")) return ResultCategory.EPISODES;
        AndroidAutoProtoMessage watchCommand = tapCommand.messageField(WATCH);
        if (!watchCommand.isEmpty()) {
            // YTM uses a Watch command to play either a song or a video; MusicVideoType chooses the category heading.
            long videoType = watchCommand.messagePath(136_656_028, 136_657_325).varintField(2);
            if (videoType == 6) return ResultCategory.UPLOADED_SONGS;
            return videoType == 2 || videoType == 3 ? ResultCategory.VIDEOS : ResultCategory.SONGS;
        }
        return ResultCategory.OTHER;
    }

    static boolean hasPlaybackAction(AndroidAutoProtoMessage command) {
        AndroidAutoProtoMessage watch = command.messageField(WATCH);
        return !watch.stringField(1).isEmpty() || !watch.stringField(2).isEmpty()
                || !command.messageField(WATCH_PLAYLIST).stringField(1).isEmpty();
    }

    private static AndroidAutoProtoMessage podcastPlayCommand(AndroidAutoProtoMessage tapCommand) {
        String browseId = tapCommand.messageField(BROWSE).stringField(2);
        return playlistPlayCommand(browseId.substring(4), false);
    }

    private static AndroidAutoProtoMessage playlistPlayCommand(String playlistId, boolean uploaded) {
        return new AndroidAutoProtoMessage(AndroidAutoPlaybackCommands.orderedPlaylist(playlistId, uploaded));
    }

    /** Finds a menu play command when the result's tap opens a page instead of starting playback. */
    private static AndroidAutoProtoMessage menuPlayCommand(AndroidAutoProtoMessage menuMessage) {
        for (AndroidAutoProtoMessage menuEntryMessage : menuMessage.repeatedMessageField(1)) {
            AndroidAutoProtoMessage playCommand = menuEntryMessage.messagePath(66_441_108, 3);
            if (hasPlaybackAction(playCommand)) return playCommand;
            for (AndroidAutoProtoMessage buttonMessage : menuEntryMessage.messagePath(ELEMENT_RENDERER, COMPONENT_RENDERER, 1,
                    COMPONENT_DATA_MESSAGE, 5, 33_556_355).repeatedMessageField(4)) {
                for (AndroidAutoProtoMessage actionMessage : buttonMessage.messagePath(3, 170_382_688).repeatedMessageField(1)) {
                    playCommand = actionMessage.messageField(INNERTUBE_COMMAND);
                    if (hasPlaybackAction(playCommand)) return playCommand;
                }
            }
        }
        return new AndroidAutoProtoMessage(new byte[0]);
    }

    private static AndroidAutoProtoMessage episodePlayCommand(AndroidAutoProtoMessage menuMessage) {
        for (AndroidAutoProtoMessage menuEntryMessage : menuMessage.repeatedMessageField(1)) {
            // Episode menus put the Watch command inside Play next or Add to queue.
            // Keep its playback context, but omit the wrapper that specifies queue insertion.
            for (int menuRenderer : new int[]{66_441_155, 77_258_115}) {
                for (AndroidAutoProtoMessage queueItem : menuEntryMessage.messagePath(menuRenderer, 3, 163_162_354)
                        .repeatedMessageField(1)) {
                    AndroidAutoProtoMessage playCommand = queueItem.messageField(4);
                    if (!playCommand.messageField(WATCH).stringField(1).isEmpty()) return playCommand;
                }
            }
        }
        return new AndroidAutoProtoMessage(new byte[0]);
    }

}
