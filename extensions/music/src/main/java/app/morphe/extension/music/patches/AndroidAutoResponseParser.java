/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
        EPISODES("library_episodes_shelf_title"),
        OTHER("morphe_music_android_auto_other_results");

        final String titleResource;
        ResultCategory(String titleResource) { this.titleResource = titleResource; }
    }

    /**
     * {@code tapStartsPlayback} keeps direct-play results on YTM's original command.
     * Menu or Play-button commands may be replaced to start albums and playlists in order.
     */
    record SearchResult(String title, String subtitle, String artworkUrl, byte[] playCommandBytes,
                        boolean tapStartsPlayback, ResultCategory category) {}
    static List<SearchResult> parseSearchResults(byte[] response) {
        if (response == null) throw new IllegalArgumentException("Missing search response");
        ProtoMessage responseMessage = new ProtoMessage(response);
        ProtoMessage contentsMessage = responseMessage.messageField(4);
        ProtoMessage sectionsMessage = contentsMessage.messageField(SECTION_LIST);
        if (sectionsMessage.isEmpty()) {
            // Some responses place results inside the "YT Music" search tab.
            for (ProtoMessage tabMessage : contentsMessage.messageField(TABBED_SEARCH).repeatedMessageField(1)) {
                sectionsMessage = tabMessage.messagePath(TAB, 4, SECTION_LIST);
                if (!sectionsMessage.isEmpty()) break;
            }
        }
        if (sectionsMessage.isEmpty()) throw new IllegalArgumentException("Unrecognized search contents");
        return readSections(sectionsMessage);
    }

    private static List<SearchResult> readSections(ProtoMessage sectionsMessage) {
        List<SearchResult> results = new ArrayList<>();
        for (ProtoMessage sectionMessage : sectionsMessage.repeatedMessageField(1)) {
            ProtoMessage shelfMessage = sectionMessage.messageField(MUSIC_SHELF);
            if (!shelfMessage.isEmpty()) {
                readShelf(results, shelfMessage);
            }
            for (ProtoMessage containerData : sectionMessage.messageField(ITEM_SECTION).repeatedMessageField(1)) {
                ProtoMessage resultData = containerData.messagePath(ELEMENT_RENDERER, COMPONENT_RENDERER, 1,
                        COMPONENT_DATA_MESSAGE, 5);
                for (ProtoMessage searchResultMessage : resultData.messageField(SEARCH_RESULTS_MESSAGE)
                        .repeatedMessageField(1)) {
                    addSearchResult(results, searchResultMessage, RESULT_TAP_COMMAND_FIELD);
                }
                ProtoMessage topResult = resultData.messagePath(TOP_RESULT, 1);
                addSearchResult(results, topResult.messageField(5), TOP_RESULT_TAP_COMMAND_FIELD);
                for (ProtoMessage searchResultMessage : topResult.repeatedMessageField(7))
                    addSearchResult(results, searchResultMessage, RESULT_TAP_COMMAND_FIELD);
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static void readShelf(List<SearchResult> results, ProtoMessage shelfMessage) {
        for (ProtoMessage shelfItemMessage : shelfMessage.repeatedMessageField(2)) {
            ProtoMessage searchResultMessage = shelfItemMessage.messageField(161_429_595);
            ProtoMessage tapCommand = searchResultMessage.messageField(5);
            boolean tapStartsPlayback = hasPlaybackAction(tapCommand);
            String title = textRuns(searchResultMessage.messageField(3));
            if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) continue;
            // If tapping opens a page on the phone, use its menu's play action.
            ProtoMessage menuMessage = searchResultMessage.messagePath(9, 66_439_850);
            ProtoMessage playCommand = tapStartsPlayback ? tapCommand : menuPlayCommand(menuMessage);
            if (!hasPlaybackAction(playCommand) && resultCategory(tapCommand) == ResultCategory.EPISODES)
                playCommand = episodePlayCommand(menuMessage);
            results.add(new SearchResult(title, textRuns(searchResultMessage.messageField(4)),
                    artwork(searchResultMessage.messagePath(1, 164_480_666, 1)),
                    playCommand.serializedBytes(), tapStartsPlayback, resultCategory(tapCommand)));
        }
    }

    private static String textRuns(ProtoMessage textMessage) {
        StringBuilder value = new StringBuilder();
        for (ProtoMessage textRun : textMessage.repeatedMessageField(1)) value.append(textRun.stringField(1));
        return value.toString();
    }

    private static String artwork(ProtoMessage thumbnailsMessage) {
        String url = "";
        for (ProtoMessage thumbnailMessage : thumbnailsMessage.repeatedMessageField(1)) {
            String candidate = thumbnailMessage.stringField(1);
            if (candidate.startsWith("https://")) url = candidate;
        }
        return url;
    }

    private static void addSearchResult(List<SearchResult> results, ProtoMessage searchResultMessage,
                                        int tapCommandField) {
        if (searchResultMessage.isEmpty()) return;
        boolean isTopResult = tapCommandField == TOP_RESULT_TAP_COMMAND_FIELD;
        String title = searchResultMessage.stringField(2);
        ProtoMessage tapCommand = searchResultMessage.messagePath(tapCommandField, INNERTUBE_COMMAND);
        boolean tapStartsPlayback = hasPlaybackAction(tapCommand);
        if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) return;
        String url = artwork(searchResultMessage.messagePath(1, 1));
        ProtoMessage playCommand = tapCommand;
        if (!tapStartsPlayback) {
            ProtoMessage menuMessage = searchResultMessage.messagePath(isTopResult ? 9 : 5,
                    INNERTUBE_COMMAND, 98_150_882, 1, 66_439_850);
            // Use the menu's play action without loading the result's phone page.
            playCommand = menuPlayCommand(menuMessage);
            // Top results expose their primary play action separately from the menu.
            if (isTopResult) {
                for (ProtoMessage buttonMessage : searchResultMessage.messageField(14).repeatedMessageField(1)) {
                    ProtoMessage buttonCommand = buttonMessage.messagePath(2, 6, INNERTUBE_COMMAND);
                    if (hasPlaybackAction(buttonCommand)) { playCommand = buttonCommand; break; }
                }
            }
            if (!hasPlaybackAction(playCommand) && resultCategory(tapCommand) == ResultCategory.EPISODES)
                playCommand = episodePlayCommand(menuMessage);
        }
        results.add(new SearchResult(title, searchResultMessage.stringField(3), url,
                playCommand.serializedBytes(), tapStartsPlayback,
                isTopResult ? ResultCategory.TOP_RESULT : resultCategory(tapCommand)));
    }

    private static ResultCategory resultCategory(ProtoMessage tapCommand) {
        String browseId = tapCommand.messageField(BROWSE).stringField(2);
        if (browseId.startsWith("MPRE")) return ResultCategory.ALBUMS;
        if (browseId.startsWith("UC")) return ResultCategory.ARTISTS;
        if (browseId.startsWith("VL")) return ResultCategory.PLAYLISTS;
        if (browseId.startsWith("MPED")) return ResultCategory.EPISODES;
        ProtoMessage watchCommand = tapCommand.messageField(WATCH);
        if (!watchCommand.isEmpty()) {
            // YTM uses a Watch command to play either a song or a video; MusicVideoType chooses the category heading.
            long videoType = watchCommand.messagePath(136_656_028, 136_657_325).varintField(2);
            return videoType == 2 || videoType == 3 ? ResultCategory.VIDEOS : ResultCategory.SONGS;
        }
        return ResultCategory.OTHER;
    }

    static boolean hasPlaybackAction(ProtoMessage command) {
        return !command.messageField(WATCH).isEmpty() || !command.messageField(WATCH_PLAYLIST).isEmpty();
    }

    /** Finds a menu play command when the result's tap opens a page instead of starting playback. */
    private static ProtoMessage menuPlayCommand(ProtoMessage menuMessage) {
        for (ProtoMessage menuEntryMessage : menuMessage.repeatedMessageField(1)) {
            ProtoMessage playCommand = menuEntryMessage.messagePath(66_441_108, 3);
            if (hasPlaybackAction(playCommand)) return playCommand;
            for (ProtoMessage buttonMessage : menuEntryMessage.messagePath(ELEMENT_RENDERER, COMPONENT_RENDERER, 1,
                    COMPONENT_DATA_MESSAGE, 5, 33_556_355).repeatedMessageField(4)) {
                for (ProtoMessage actionMessage : buttonMessage.messagePath(3, 170_382_688).repeatedMessageField(1)) {
                    playCommand = actionMessage.messageField(INNERTUBE_COMMAND);
                    if (hasPlaybackAction(playCommand)) return playCommand;
                }
            }
        }
        return new ProtoMessage(new byte[0]);
    }

    private static ProtoMessage episodePlayCommand(ProtoMessage menuMessage) {
        for (ProtoMessage menuEntryMessage : menuMessage.repeatedMessageField(1)) {
            // Episode menus put the Watch command inside Play next or Add to queue.
            // Keep its playback context, but omit the wrapper that specifies queue insertion.
            for (int menuRenderer : new int[]{66_441_155, 77_258_115}) {
                for (ProtoMessage queueItem : menuEntryMessage.messagePath(menuRenderer, 3, 163_162_354)
                        .repeatedMessageField(1)) {
                    ProtoMessage playCommand = queueItem.messageField(4);
                    if (!playCommand.messageField(WATCH).stringField(1).isEmpty()) return playCommand;
                }
            }
        }
        return new ProtoMessage(new byte[0]);
    }

    /** Reads selected protobuf fields as byte slices, without YTM's generated message classes. */
    static final class ProtoMessage {
        private static final byte[] EMPTY = new byte[0];
        private final byte[] data;
        private final int start;
        private final int end;

        ProtoMessage(byte[] data) { this(data, 0, data.length); }

        private ProtoMessage(byte[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = end;
        }

        boolean isEmpty() { return start == end; }
        String utf8Text() { return new String(data, start, end - start, StandardCharsets.UTF_8); }
        String stringField(int fieldNumber) { return messageField(fieldNumber).utf8Text(); }
        byte[] serializedBytes() { return Arrays.copyOfRange(data, start, end); }

        ProtoMessage messagePath(int... fieldNumbers) {
            ProtoMessage nestedMessage = this;
            for (int fieldNumber : fieldNumbers) nestedMessage = nestedMessage.messageField(fieldNumber);
            return nestedMessage;
        }

        ProtoMessage messageField(int fieldNumber) {
            return messageField(fieldNumber, 2);
        }

        private ProtoMessage messageField(int fieldNumber, int wireType) {
            List<ProtoMessage> matches = repeatedMessageField(fieldNumber, wireType);
            if (matches.size() > 1) throw new IllegalArgumentException("Repeated singular field: " + fieldNumber);
            return matches.isEmpty() ? new ProtoMessage(EMPTY) : matches.get(0);
        }

        long varintField(int fieldNumber) {
            ProtoMessage field = messageField(fieldNumber, 0);
            return field.isEmpty() ? 0 : field.varint(new int[]{field.start});
        }

        List<ProtoMessage> repeatedMessageField(int fieldNumber) {
            return repeatedMessageField(fieldNumber, 2);
        }

        private List<ProtoMessage> repeatedMessageField(int fieldNumber, int expectedWireType) {
            List<ProtoMessage> matches = new ArrayList<>();
            int[] position = {start};
            while (position[0] < end) {
                long tag = varint(position);
                // A protobuf tag packs the field number above three wire-type bits.
                long currentFieldNumber = tag >>> 3;
                int wireType = (int) (tag & 7);
                if (currentFieldNumber == 0 || currentFieldNumber >= (1L << 29))
                    throw new IllegalArgumentException("Invalid protobuf tag");
                if (currentFieldNumber == fieldNumber && wireType != expectedWireType)
                    throw new IllegalArgumentException("Unexpected protobuf field type");
                if (wireType == 0) {
                    int valueStart = position[0];
                    varint(position);
                    if (currentFieldNumber == fieldNumber) matches.add(new ProtoMessage(data, valueStart, position[0]));
                } else {
                    long length;
                    if (wireType == 2) length = varint(position);
                    else if (wireType == 1) length = 8;
                    else if (wireType == 5) length = 4;
                    else throw new IllegalArgumentException("Unsupported protobuf wire type");
                    if (length < 0 || length > end - position[0]) throw new IllegalArgumentException("Truncated protobuf field");
                    if (currentFieldNumber == fieldNumber) {
                        matches.add(new ProtoMessage(data, position[0], position[0] + (int) length));
                    }
                    position[0] += (int) length;
                }
            }
            return matches;
        }

        private long varint(int[] position) {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position[0] >= end) throw new IllegalArgumentException("Truncated protobuf varint");
                int value = data[position[0]++] & 255;
                if (shift == 63 && value > 1) throw new IllegalArgumentException("Overflowed protobuf varint");
                result |= (long) (value & 127) << shift;
                if ((value & 128) == 0) return result;
            }
            throw new IllegalArgumentException("Invalid protobuf varint");
        }
    }
}
