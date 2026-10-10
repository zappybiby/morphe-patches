/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AndroidAutoHomeResponseParser {
    // Server field numbers let us read responses without depending on YTM's generated class names.
    private static final int TAB = 58_174_010;
    private static final int SECTION_LIST = 49_399_797;
    private static final int ITEM_SECTION = 50_195_462;
    private static final int GRID = 51_805_506;
    private static final int ELEMENT = 153_515_154;
    private static final int COMPONENT = 172_660_663;
    private static final int COMPONENT_DATA = 168_777_401;
    private static final int SEARCH_ROW = 410_090_561;
    private static final int TOP_RESULT = 1_223;
    private static final int INNERTUBE_COMMAND = 169_495_254;
    static final int WATCH = 48_687_757;
    static final int WATCH_PLAYLIST = 52_666_186;
    static final int BROWSE = 48_687_626;
    private static final int MUSIC_SHELF = 91_303_872;
    private static final int PLAYLIST_SHELF = 175_617_300;

    /** Phone cards may open a page instead of playing; playCommand provides a play action when available. */
    record Item(String title, String subtitle, String artworkUrl, byte[] command, boolean playable,
                byte[] playCommand, String group, String groupBrowseId) {
        Item(String title, String subtitle, String artworkUrl, byte[] command, boolean playable,
             byte[] playCommand) {
            this(title, subtitle, artworkUrl, command, playable, playCommand, "", "");
        }
    }

    record HomePage(List<Item> items, String continuation) {}

    static HomePage homePage(byte[] response) {
        AndroidAutoProtoMessage sections = sections(response);
        List<Item> items = new ArrayList<>();
        for (Item item : readSections(sections)) {
            String id = new AndroidAutoProtoMessage(item.command()).messageField(BROWSE).stringField(2);
            if (!item.playable() && !id.startsWith("UC") && !id.startsWith("MPRE") && !id.startsWith("VL")) continue;
            byte[] play = item.playable() ? item.command() : item.playCommand();
            if (isPlayable(new AndroidAutoProtoMessage(play))) {
                items.add(item);
            }
        }
        String continuation = "";
        for (AndroidAutoProtoMessage command : sections.repeatedMessageField(2)) {
            continuation = command.messageField(52_047_593).stringField(1);
            if (!continuation.isEmpty()) break;
        }
        return new HomePage(List.copyOf(items), continuation);
    }

    static List<Item> collection(byte[] response) {
        return readSections(sections(response));
    }

    private static AndroidAutoProtoMessage sections(byte[] response) {
        checkSize(response);
        AndroidAutoProtoMessage root = new AndroidAutoProtoMessage(response);
        // Home's later shelves arrive as a section-list continuation, without the initial tab wrapper.
        AndroidAutoProtoMessage continued = root.messagePath(10, SECTION_LIST);
        if (!continued.isEmpty()) return continued;
        AndroidAutoProtoMessage contents = root.messageField(9);
        AndroidAutoProtoMessage sections = contents.messageField(SECTION_LIST);
        if (sections.isEmpty()) {
            for (AndroidAutoProtoMessage tab : contents.messageField(58_173_949).repeatedMessageField(1)) {
                sections = tab.messagePath(TAB, 4, SECTION_LIST);
                if (!sections.isEmpty()) break;
            }
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("Unrecognized browse contents");
        return sections;
    }

    private static void checkSize(byte[] response) {
        if (response == null || response.length > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid response size");
        }
    }

    private static List<Item> readSections(AndroidAutoProtoMessage sections) {
        List<Item> items = new ArrayList<>();
        for (AndroidAutoProtoMessage section : sections.repeatedMessageField(1)) {
            AndroidAutoProtoMessage shelf = section.messageField(MUSIC_SHELF);
            if (!shelf.isEmpty()) {
                readShelf(items, shelf, 2);
            }
            AndroidAutoProtoMessage playlist = section.messageField(PLAYLIST_SHELF);
            if (!playlist.isEmpty()) {
                readShelf(items, playlist, 3);
            }
            List<AndroidAutoProtoMessage> contents = new ArrayList<>(section.messageField(ITEM_SECTION).repeatedMessageField(1));
            contents.addAll(section.messageField(GRID).repeatedMessageField(1));
            for (AndroidAutoProtoMessage content : contents) {
                AndroidAutoProtoMessage model = content.messagePath(ELEMENT, COMPONENT, 1, COMPONENT_DATA, 5);
                int groupStart = items.size();
                String group = model.messagePath(487_343_630, 5, 2).stringField(1);
                if (group.isEmpty()) group = model.messagePath(404_005_902, 1, 5).stringField(1);
                if (group.isEmpty()) group = model.messagePath(405_953_475, 5).stringField(1);
                String groupBrowseId = model.messagePath(487_343_630, 5, 2, 5, INNERTUBE_COMMAND, BROWSE).stringField(2);
                // Phone Home uses a separate Speed dial card layout.
                for (AndroidAutoProtoMessage card : model.messagePath(487_343_630, 5).repeatedMessageField(1)) {
                    AndroidAutoProtoMessage command = card.messagePath(9, INNERTUBE_COMMAND);
                    boolean playable = isPlayable(command);
                    if ((!playable && command.messageField(BROWSE).isEmpty()) || card.stringField(1).isEmpty()) continue;
                    items.add(new Item(card.stringField(1), speedDialSubtitle(card), artwork(card.messagePath(2, 1)), command.serializedBytes(), playable,
                            card.messagePath(10, INNERTUBE_COMMAND).serializedBytes()));
                }
                // Full collection pages use grid cards rather than Home's compact cards.
                addItem(items, model.messagePath(1_105, 1), 4, 1, 2, 3);
                for (AndroidAutoProtoMessage row : model.messageField(SEARCH_ROW).repeatedMessageField(1)) {
                    addItem(items, row, 4);
                }
                AndroidAutoProtoMessage top = model.messagePath(TOP_RESULT, 1);
                addItem(items, top.messageField(5), 8);
                for (AndroidAutoProtoMessage row : top.repeatedMessageField(7)) addItem(items, row, 4);
                for (AndroidAutoProtoMessage row : model.messageField(405_953_475).repeatedMessageField(6)) addItem(items, row, 4);
                for (AndroidAutoProtoMessage card : model.messagePath(404_005_902, 1).repeatedMessageField(3)) {
                    addItem(items, card, 4, 1, 2, 3);
                }
                if (!group.isEmpty()) {
                    for (int i = groupStart; i < items.size(); i++) {
                        Item item = items.get(i);
                        items.set(i, new Item(item.title(), item.subtitle(), item.artworkUrl(), item.command(),
                                item.playable(), item.playCommand(), group, groupBrowseId));
                    }
                }
            }
        }
        return Collections.unmodifiableList(items);
    }

    private static String speedDialSubtitle(AndroidAutoProtoMessage card) {
        // Compact phone cards keep their creator/collection metadata in the menu header.
        for (AndroidAutoProtoMessage entry : card.messagePath(4, INNERTUBE_COMMAND, 98_150_882, 1, 66_439_850)
                .repeatedMessageField(1)) {
            String subtitle = entry.messagePath(ELEMENT, COMPONENT, 1, COMPONENT_DATA, 5,
                    413_471_385, 1, 1_051, 3).stringField(1);
            if (!subtitle.isEmpty()) return subtitle;
        }
        return "";
    }

    private static void readShelf(List<Item> items, AndroidAutoProtoMessage shelf, int rowsField) {
        for (AndroidAutoProtoMessage content : shelf.repeatedMessageField(rowsField)) {
            AndroidAutoProtoMessage row = content.messageField(161_429_595);
            AndroidAutoProtoMessage command = row.messageField(5);
            boolean playable = isPlayable(command);
            String title = textRuns(row.messageField(3));
            if (title.isEmpty() || (!playable && command.messageField(BROWSE).isEmpty())) continue;
            AndroidAutoProtoMessage menu = row.messagePath(9, 66_439_850);
            AndroidAutoProtoMessage play = playable ? command : menuPlayCommand(menu);
            if (!isPlayable(play) && command.messageField(BROWSE).stringField(2).startsWith("MPED")) {
                play = episodePlayCommand(menu);
            }
            items.add(new Item(title, textRuns(row.messageField(4)),
                    artwork(row.messagePath(1, 164_480_666, 1)), command.serializedBytes(), playable,
                    play.serializedBytes()));
        }
    }

    private static String textRuns(AndroidAutoProtoMessage text) {
        StringBuilder value = new StringBuilder();
        for (AndroidAutoProtoMessage run : text.repeatedMessageField(1)) value.append(run.stringField(1));
        return value.toString();
    }

    private static String artwork(AndroidAutoProtoMessage thumbnails) {
        String url = "";
        for (AndroidAutoProtoMessage thumbnail : thumbnails.repeatedMessageField(1)) {
            String candidate = thumbnail.stringField(1);
            if (candidate.startsWith("https://")) url = candidate;
        }
        return url;
    }

    private static void addItem(List<Item> items, AndroidAutoProtoMessage row, int commandField) {
        addItem(items, row, commandField, 2, 3, 1);
    }

    private static void addItem(List<Item> items, AndroidAutoProtoMessage row, int commandField,
                                int titleField, int subtitleField, int imageField) {
        if (row.isEmpty()) return;
        String title = row.stringField(titleField);
        AndroidAutoProtoMessage command = row.messagePath(commandField, INNERTUBE_COMMAND);
        boolean playable = isPlayable(command);
        if (title.isEmpty() || (!playable && command.messageField(BROWSE).isEmpty())) return;
        String url = artwork(row.messagePath(imageField, 1));
        AndroidAutoProtoMessage play = command;
        if (!playable) {
            AndroidAutoProtoMessage menu = row.messagePath(commandField == 8 ? 9 : 5,
                    INNERTUBE_COMMAND, 98_150_882, 1, 66_439_850);
            play = menuPlayCommand(menu);
            if (!isPlayable(play) && command.messageField(BROWSE).stringField(2).startsWith("MPED")) {
                play = episodePlayCommand(menu);
            }
            // Top results expose their primary play action separately from the menu.
            if (commandField == 8) {
                for (AndroidAutoProtoMessage button : row.messageField(14).repeatedMessageField(1)) {
                    AndroidAutoProtoMessage action = button.messagePath(2, 6, INNERTUBE_COMMAND);
                    if (isPlayable(action)) { play = action; break; }
                }
            }
        }
        items.add(new Item(title, row.stringField(subtitleField), url, command.serializedBytes(), playable, play.serializedBytes()));
    }

    static boolean isPlayable(AndroidAutoProtoMessage command) {
        AndroidAutoProtoMessage watch = command.messageField(WATCH);
        return !watch.stringField(1).isEmpty() || !watch.stringField(2).isEmpty()
                || !command.messageField(WATCH_PLAYLIST).stringField(1).isEmpty();
    }

    /** Reads an existing play command from the menu, avoiding a separate request for playback details. */
    private static AndroidAutoProtoMessage menuPlayCommand(AndroidAutoProtoMessage menu) {
        for (AndroidAutoProtoMessage entry : menu.repeatedMessageField(1)) {
            AndroidAutoProtoMessage command = entry.messagePath(66_441_108, 3);
            if (isPlayable(command)) return command;
            for (AndroidAutoProtoMessage button : entry.messagePath(ELEMENT, COMPONENT, 1, COMPONENT_DATA, 5, 33_556_355).repeatedMessageField(4)) {
                for (AndroidAutoProtoMessage action : button.messagePath(3, 170_382_688).repeatedMessageField(1)) {
                    command = action.messageField(INNERTUBE_COMMAND);
                    if (isPlayable(command)) return command;
                }
            }
        }
        return new AndroidAutoProtoMessage(new byte[0]);
    }

    private static AndroidAutoProtoMessage episodePlayCommand(AndroidAutoProtoMessage menu) {
        // Episode menus wrap Watch in Play next or Add to queue. Keep its context without the queue-insertion action.
        for (AndroidAutoProtoMessage entry : menu.repeatedMessageField(1)) {
            for (int renderer : new int[]{66_441_155, 77_258_115}) {
                for (AndroidAutoProtoMessage item : entry.messagePath(renderer, 3, 163_162_354).repeatedMessageField(1)) {
                    AndroidAutoProtoMessage command = item.messageField(4);
                    if (!command.messageField(WATCH).stringField(1).isEmpty()) return command;
                }
            }
        }
        return new AndroidAutoProtoMessage(new byte[0]);
    }

}
