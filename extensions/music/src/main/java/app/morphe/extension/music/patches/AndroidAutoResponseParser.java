/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class AndroidAutoResponseParser {
    // Server field numbers let us read responses without depending on YTM's generated class names.
    private static final int TAB = 58_174_010;
    private static final int SECTION_LIST = 49_399_797;
    private static final int ITEM_SECTION = 50_195_462;
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
                byte[] playCommand) {}
    /** continuation is empty when the server provides no next-page token. */
    record Page(List<Item> items, String continuation) {}

    static Page home(byte[] response) {
        Page page = collection(response);
        List<Item> items = new ArrayList<>();
        for (Item item : page.items()) {
            String id = new Message(item.command()).message(BROWSE).string(2);
            if (!item.playable() && !id.startsWith("UC") && !id.startsWith("MPRE") && !id.startsWith("VL")) continue;
            byte[] play = item.playable() ? item.command() : item.playCommand();
            if (isPlayable(new Message(play))) {
                items.add(new Item(item.title(), item.subtitle(), item.artworkUrl(), play, true, play));
            }
        }
        return new Page(items, "");
    }

    static Page collection(byte[] response) {
        checkSize(response);
        Message root = new Message(response);
        Message next = root.path(10, MUSIC_SHELF);
        if (!next.isEmpty()) {
            List<Item> items = new ArrayList<>();
            readShelf(items, next, 2);
            return new Page(List.copyOf(items), continuation(next));
        }
        Message contents = root.message(9);
        Message sections = contents.message(SECTION_LIST);
        if (sections.isEmpty()) {
            for (Message tab : contents.message(58_173_949).messages(1)) {
                sections = tab.path(TAB, 4, SECTION_LIST);
                if (!sections.isEmpty()) break;
            }
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("Unrecognized browse contents");
        return readSections(sections);
    }

    private static void checkSize(byte[] response) {
        if (response == null || response.length > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid response size");
        }
    }

    private static Page readSections(Message sections) {
        List<Item> items = new ArrayList<>();
        String continuation = "";
        for (Message section : sections.messages(1)) {
            Message shelf = section.message(MUSIC_SHELF);
            if (!shelf.isEmpty()) {
                readShelf(items, shelf, 2);
                continuation = continuation(shelf);
            }
            Message playlist = section.message(PLAYLIST_SHELF);
            if (!playlist.isEmpty()) {
                readShelf(items, playlist, 3);
                continuation = playlist.path(10, 52_047_593).string(1);
            }
            for (Message content : section.message(ITEM_SECTION).messages(1)) {
                Message model = content.path(ELEMENT, COMPONENT, 1, COMPONENT_DATA, 5);
                // Phone Home uses a separate Speed dial card layout.
                for (Message card : model.path(487_343_630, 5).messages(1)) {
                    Message command = card.path(9, INNERTUBE_COMMAND);
                    boolean playable = isPlayable(command);
                    if ((!playable && command.message(BROWSE).isEmpty()) || card.string(1).isEmpty()) continue;
                    items.add(new Item(card.string(1), "", artwork(card.path(2, 1)), command.bytes(), playable,
                            card.path(10, INNERTUBE_COMMAND).bytes()));
                }
                for (Message row : model.message(SEARCH_ROW).messages(1)) {
                    addItem(items, row, 4);
                }
                Message top = model.path(TOP_RESULT, 1);
                addItem(items, top.message(5), 8);
                for (Message row : top.messages(7)) addItem(items, row, 4);
                for (Message row : model.message(405_953_475).messages(6)) addItem(items, row, 4);
                for (Message card : model.path(404_005_902, 1).messages(3)) {
                    addItem(items, card, 4, 1, 2, 3);
                }
            }
        }
        return new Page(Collections.unmodifiableList(items), continuation);
    }

    private static String continuation(Message shelf) {
        return shelf.path(13, 52_047_593).string(1);
    }

    private static void readShelf(List<Item> items, Message shelf, int rowsField) {
        for (Message content : shelf.messages(rowsField)) {
            Message row = content.message(161_429_595);
            Message command = row.message(5);
            boolean playable = isPlayable(command);
            String title = textRuns(row.message(3));
            if (title.isEmpty() || (!playable && command.message(BROWSE).isEmpty())) continue;
            items.add(new Item(title, textRuns(row.message(4)),
                    artwork(row.path(1, 164_480_666, 1)), command.bytes(), playable,
                    playable ? command.bytes() : menuPlayCommand(row.path(9, 66_439_850)).bytes()));
        }
    }

    private static String textRuns(Message text) {
        StringBuilder value = new StringBuilder();
        for (Message run : text.messages(1)) value.append(run.string(1));
        return value.toString();
    }

    private static String artwork(Message thumbnails) {
        String url = "";
        for (Message thumbnail : thumbnails.messages(1)) {
            String candidate = thumbnail.string(1);
            if (candidate.startsWith("https://")) url = candidate;
        }
        return url;
    }

    private static void addItem(List<Item> items, Message row, int commandField) {
        addItem(items, row, commandField, 2, 3, 1);
    }

    private static void addItem(List<Item> items, Message row, int commandField,
                                int titleField, int subtitleField, int imageField) {
        if (row.isEmpty()) return;
        String title = row.string(titleField);
        Message command = row.path(commandField, INNERTUBE_COMMAND);
        boolean playable = isPlayable(command);
        if (title.isEmpty() || (!playable && command.message(BROWSE).isEmpty())) return;
        String url = artwork(row.path(imageField, 1));
        Message play = command;
        if (!playable) {
            play = menuPlayCommand(row.path(commandField == 8 ? 9 : 5,
                    INNERTUBE_COMMAND, 98_150_882, 1, 66_439_850));
            // Top results expose their primary play action separately from the menu.
            if (commandField == 8) {
                for (Message button : row.message(14).messages(1)) {
                    Message action = button.path(2, 6, INNERTUBE_COMMAND);
                    if (isPlayable(action)) { play = action; break; }
                }
            }
        }
        items.add(new Item(title, row.string(subtitleField), url, command.bytes(), playable, play.bytes()));
    }

    static boolean isPlayable(Message command) {
        return !command.message(WATCH).isEmpty() || !command.message(WATCH_PLAYLIST).isEmpty();
    }

    /** Reads an existing play command from the menu, avoiding a separate request for playback details. */
    private static Message menuPlayCommand(Message menu) {
        for (Message entry : menu.messages(1)) {
            Message command = entry.path(66_441_108, 3);
            if (isPlayable(command)) return command;
            for (Message button : entry.path(ELEMENT, COMPONENT, 1, COMPONENT_DATA, 5, 33_556_355).messages(4)) {
                for (Message action : button.path(3, 170_382_688).messages(1)) {
                    command = action.message(INNERTUBE_COMMAND);
                    if (isPlayable(command)) return command;
                }
            }
        }
        return new Message(new byte[0]);
    }

    /** Reads selected fields without building a complete protobuf message tree. */
    static final class Message {
        private static final byte[] EMPTY = new byte[0];
        private final byte[] data;
        private final int start;
        private final int end;

        Message(byte[] data) { this(data, 0, data.length); }

        private Message(byte[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = end;
        }

        boolean isEmpty() { return start == end; }
        String text() { return new String(data, start, end - start, StandardCharsets.UTF_8); }
        String string(int number) { return message(number).text(); }
        byte[] bytes() { return Arrays.copyOfRange(data, start, end); }

        Message path(int... numbers) {
            Message value = this;
            for (int number : numbers) value = value.message(number);
            return value;
        }

        Message message(int wanted) {
            return message(wanted, 2);
        }

        private Message message(int wanted, int wire) {
            List<Message> matches = messages(wanted, wire);
            if (matches.size() > 1) throw new IllegalArgumentException("Repeated singular field: " + wanted);
            return matches.isEmpty() ? new Message(EMPTY) : matches.get(0);
        }

        List<Message> messages(int wanted) {
            return messages(wanted, 2);
        }

        private List<Message> messages(int wanted, int expectedWire) {
            List<Message> matches = new ArrayList<>();
            int[] position = {start};
            while (position[0] < end) {
                long tag = varint(position);
                long number = tag >>> 3;
                int wire = (int) (tag & 7);
                if (number == 0 || number >= (1L << 29)) throw new IllegalArgumentException("Invalid protobuf tag");
                if (number == wanted && wire != expectedWire) throw new IllegalArgumentException("Unexpected protobuf field type");
                if (wire == 0) {
                    int valueStart = position[0];
                    varint(position);
                    if (number == wanted) matches.add(new Message(data, valueStart, position[0]));
                } else {
                    long length;
                    if (wire == 2) length = varint(position);
                    else if (wire == 1) length = 8;
                    else if (wire == 5) length = 4;
                    else throw new IllegalArgumentException("Unsupported protobuf wire type");
                    if (length < 0 || length > end - position[0]) throw new IllegalArgumentException("Truncated protobuf field");
                    if (number == wanted) {
                        if (wire != 2) throw new IllegalArgumentException("Unexpected protobuf field type");
                        matches.add(new Message(data, position[0], position[0] + (int) length));
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
