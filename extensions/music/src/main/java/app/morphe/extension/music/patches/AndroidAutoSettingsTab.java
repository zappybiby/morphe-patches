/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.ResourceUtils.getString;
import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import app.morphe.extension.shared.Utils;

public final class AndroidAutoSettingsTab {
    /** The media browser service provides the settings controller for the current account. */
    public interface Source {
        NativeSettings patch_getNativeSettings();
    }

    public interface NativeSettings {
        String patch_getStorageSummary(Context context);
        boolean patch_showDeviceFilesAvailable(Context context);
        boolean patch_showDeviceFiles();
        boolean patch_wifiOnly();
        boolean patch_smartDownloadsAvailable();
        boolean patch_smartDownloads();
        void patch_setShowDeviceFiles(boolean value);
        void patch_setWifiOnly(boolean value);
        void patch_setSmartDownloads(boolean value);
    }

    static final String SETTINGS_MEDIA_ID = "morphe:aa:settings";
    private static final String ROW_PREFIX = SETTINGS_MEDIA_ID + ":row:";
    private static final String ACTION_IDS = "androidx.media.utils.extras.CUSTOM_BROWSER_ACTION_ID_LIST";
    private static final String STORAGE = "storage";
    private static final String DEVICE_FILES = "device-files";
    private static final String WIFI_ONLY = "wifi-only";
    private static final String SMART_DOWNLOADS = "smart-downloads";
    private static final String[] SWITCHES = {DEVICE_FILES, WIFI_ONLY, SMART_DOWNLOADS};

    private AndroidAutoSettingsTab() {}

    /** Set by GmsCore support when it removes the stock settings intent. */
    public static boolean replacesStockSettings() {
        return false;
    }

    static String itemId(String key) {
        return ROW_PREFIX + key;
    }

    @Nullable
    static String itemKey(@Nullable String id) {
        if (id == null || !id.startsWith(ROW_PREFIX)) return null;
        String key = id.substring(ROW_PREFIX.length());
        return titleKey(key) == null ? null : key;
    }

    @Nullable
    private static String titleKey(String key) {
        return switch (key) {
            case STORAGE -> "pref_offline_storage_title";
            case DEVICE_FILES -> "pref_show_device_files";
            case WIFI_ONLY -> "pref_offline_mixtape_only_wifi";
            case SMART_DOWNLOADS -> "auto_offline_title";
            default -> null;
        };
    }

    static MediaItem settingsTab() {
        Uri icon = Uri.parse("android.resource://" + Utils.getContext().getPackageName()
                + "/drawable/morphe_settings_screen_04_general");
        MediaDescriptionCompat description = new MediaDescriptionCompat(SETTINGS_MEDIA_ID,
                getString("settings"), null, null, null, icon, null, null);
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
    }

    static List<MediaItem> items(@Nullable NativeSettings settings,
                                 @Nullable BiFunction<String, Boolean, String> issueCommand) {
        return items(settings, false, issueCommand);
    }

    static List<MediaItem> items(@Nullable NativeSettings settings, boolean browseChoices,
                                 @Nullable BiFunction<String, Boolean, String> issueCommand) {
        if (settings == null) return Collections.emptyList();
        List<MediaItem> rows = new ArrayList<>(4);
        rows.add(row(itemId(STORAGE), getString(titleKey(STORAGE)),
                settings.patch_getStorageSummary(Utils.getContext()), 0, null));
        for (String key : SWITCHES) {
            Boolean checked = value(settings, key);
            if (checked == null) continue;
            String subtitle = stateLabel(checked);
            if (DEVICE_FILES.equals(key)) subtitle += " · " + getString("pref_show_device_files_summary");
            if (SMART_DOWNLOADS.equals(key)) {
                subtitle += " · " + getString("smart_downloads_education_brief_shelf_description");
            }
            Bundle extras = null;
            if (!browseChoices && issueCommand != null) {
                String action = issueCommand.apply(key, !checked);
                if (action != null) {
                    extras = new Bundle();
                    extras.putStringArrayList(ACTION_IDS, new ArrayList<>(Collections.singletonList(action)));
                }
            }
            rows.add(row(itemId(key), getString(titleKey(key)), subtitle,
                    browseChoices ? MediaItem.FLAG_BROWSABLE : 0, extras));
        }
        return rows;
    }

    @Nullable
    static Boolean value(@Nullable NativeSettings settings, @Nullable String key) {
        if (settings == null || key == null) return null;
        return switch (key) {
            case DEVICE_FILES -> settings.patch_showDeviceFilesAvailable(Utils.getContext())
                    ? settings.patch_showDeviceFiles() : null;
            case WIFI_ONLY -> settings.patch_wifiOnly();
            case SMART_DOWNLOADS -> settings.patch_smartDownloadsAvailable()
                    ? settings.patch_smartDownloads() : null;
            default -> null;
        };
    }

    private static String stateLabel(boolean checked) {
        return getString(checked ? "morphe_music_android_auto_setting_on" : "morphe_music_android_auto_setting_off");
    }

    static MediaItem choice(String mediaId, @Nullable String key, boolean value) {
        String title = key == null ? null : titleKey(key);
        return row(mediaId, stateLabel(value), title == null ? null : getString(title), MediaItem.FLAG_BROWSABLE, null);
    }

    static MediaItem confirm(String mediaId, String key, boolean value) {
        String resource = value ? "morphe_music_android_auto_confirm_on" : "morphe_music_android_auto_confirm_off";
        return row(mediaId, str(resource, getString(titleKey(key))), null, MediaItem.FLAG_BROWSABLE, null);
    }

    static MediaItem status(String key, @Nullable Boolean value) {
        if (value == null) return expired();
        return row(SETTINGS_MEDIA_ID + ":status:" + key, getString(titleKey(key)), stateLabel(value), 0, null);
    }

    static MediaItem expired() {
        return row(SETTINGS_MEDIA_ID + ":expired", getString("morphe_music_android_auto_expired"),
                getString("morphe_music_android_auto_open_again"), 0, null);
    }

    private static MediaItem row(String id, String title, @Nullable String subtitle, int flags,
                                 @Nullable Bundle extras) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(id, title,
                subtitle, null, null, null, extras, null);
        return new MediaItem(description, flags);
    }

    /** Applies an absolute choice to the same setting YTM uses on the phone. */
    static boolean apply(@Nullable NativeSettings settings, String key, boolean value) {
        Boolean current = value(settings, key);
        if (current == null) return false;
        if (current == value) return true;
        switch (key) {
            case DEVICE_FILES -> settings.patch_setShowDeviceFiles(value);
            case WIFI_ONLY -> settings.patch_setWifiOnly(value);
            case SMART_DOWNLOADS -> settings.patch_setSmartDownloads(value);
            default -> { return false; }
        }
        return true;
    }
}
