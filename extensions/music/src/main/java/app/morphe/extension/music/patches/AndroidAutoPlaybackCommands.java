/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Ordered playlist commands for podcasts and uploaded music. */
final class AndroidAutoPlaybackCommands {
    private static final int WATCH_PLAYLIST = 52_666_186;

    private AndroidAutoPlaybackCommands() {}

    static byte[] orderedPlaylist(String playlistId, boolean uploaded) {
        if (playlistId.isEmpty()) return new byte[0];
        // Auto's ordered WatchPlaylist action distinguishes catalog playlists from uploads.
        byte[] playbackOptions = {0x10, 0x02};
        byte[] collectionOptions = {0x10, (byte) (uploaded ? 1 : 2)};
        return message(WATCH_PLAYLIST,
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
}
