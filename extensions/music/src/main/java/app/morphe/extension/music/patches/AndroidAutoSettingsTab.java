/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.ResourceUtils.getString;
import static app.morphe.extension.shared.StringRef.str;

import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Utils;

public final class AndroidAutoSettingsTab {
    public interface Source {
        void patch_loadSettings(Rows rows);
    }

    public interface Rows {
        void add(int titleResource, String title, List<?> texts, boolean checked, @Nullable Switch listener);
    }

    public interface Switch {
        void patch_set(boolean value);
    }

    static final String SETTINGS_MEDIA_ID = "morphe:aa:settings";
    static final String ROW_PREFIX = SETTINGS_MEDIA_ID + ":row:";
    private final Map<String, Setting> settings = new LinkedHashMap<>();

    /** Set by GmsCore support when it removes the stock settings intent. */
    public static boolean replacesStockSettings() {
        return false;
    }

    static MediaItem settingsTab() {
        Uri icon = Uri.parse("android.resource://" + Utils.getContext().getPackageName()
                + "/drawable/morphe_settings_screen_04_general");
        MediaDescriptionCompat description = new MediaDescriptionCompat(SETTINGS_MEDIA_ID,
                getString("settings"), null, null, null, icon, null, null);
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
    }

    static AndroidAutoSettingsTab load(Source source) {
        var tab = new AndroidAutoSettingsTab();
        source.patch_loadSettings(tab::add);
        return tab;
    }

    private void add(int titleResource, String title, List<?> texts, boolean checked, @Nullable Switch listener) {
        String name = Utils.getContext().getResources().getResourceEntryName(titleResource);
        // Keep the media IDs Android Auto has already seen, even when YTM's numeric resource IDs change.
        String key = switch (name) {
            case "pref_offline_storage_title" -> "storage";
            case "pref_show_device_files" -> "device-files";
            case "pref_offline_mixtape_only_wifi" -> "wifi-only";
            case "auto_offline_title" -> "smart-downloads";
            default -> name;
        };
        settings.put(key, new Setting(key, title, TextUtils.join(" · ", texts), checked, listener));
    }

    @Nullable
    Setting get(String key) {
        return settings.get(key);
    }

    List<MediaItem> items() {
        List<MediaItem> rows = new ArrayList<>(settings.size());
        for (Setting setting : settings.values()) {
            String subtitle = setting.summary;
            if (setting.listener != null) {
                String state = stateLabel(setting.checked);
                subtitle = subtitle.isEmpty() ? state : state + " · " + subtitle;
            }
            rows.add(row(ROW_PREFIX + setting.key, setting.title, subtitle, setting.listener != null));
        }
        return rows;
    }

    record Setting(String key, String title, String summary, boolean checked, @Nullable Switch listener) {
        MediaItem choice(String id, boolean value) {
            return row(id, stateLabel(value), title, true);
        }

        MediaItem confirm(String id, boolean value) {
            String resource = value ? "morphe_music_android_auto_confirm_on" : "morphe_music_android_auto_confirm_off";
            return row(id, str(resource, title), null, true);
        }
    }

    static MediaItem status(@Nullable Setting setting) {
        return setting == null ? expired() : row(SETTINGS_MEDIA_ID + ":status:" + setting.key,
                setting.title, stateLabel(setting.checked), false);
    }

    static MediaItem expired() {
        return row(SETTINGS_MEDIA_ID + ":expired", getString("morphe_music_android_auto_expired"),
                getString("morphe_music_android_auto_open_again"), false);
    }

    private static String stateLabel(boolean checked) {
        return getString(checked ? "morphe_music_android_auto_setting_on" : "morphe_music_android_auto_setting_off");
    }

    private static MediaItem row(String id, String title, @Nullable String subtitle, boolean browsable) {
        var description = new MediaDescriptionCompat(id, title, subtitle, null, null, null, null, null);
        return new MediaItem(description, browsable ? MediaItem.FLAG_BROWSABLE : 0);
    }
}
