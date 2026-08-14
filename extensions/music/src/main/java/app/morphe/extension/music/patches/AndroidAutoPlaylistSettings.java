/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static java.lang.Boolean.FALSE;

import app.morphe.extension.shared.settings.BooleanSetting;

final class AndroidAutoPlaylistSettings {
    static final BooleanSetting LIST_VIEW = new BooleanSetting(
            "morphe_music_android_auto_playlist_list_view", FALSE, true);

    private AndroidAutoPlaylistSettings() {
    }
}
