/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.AndroidAutoSettingsTab.ROW_PREFIX;
import static app.morphe.extension.music.patches.AndroidAutoSettingsTab.SETTINGS_MEDIA_ID;

import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat.MediaItem;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A settings review belongs to one live Android Auto browse connection. Accessed on the main thread. */
final class AndroidAutoSettingsNavigation {
    private static final long REVIEW_LIFETIME_MS = 120_000;
    private static final String REVIEW_PREFIX = SETTINGS_MEDIA_ID + ":review:";
    private static final String APPLY_PREFIX = SETTINGS_MEDIA_ID + ":apply:";

    private static final class Review {
        final String key;
        final boolean value;
        final String id = REVIEW_PREFIX + UUID.randomUUID();
        final long issuedAt = SystemClock.elapsedRealtime();
        @Nullable String applyId;
        boolean used;

        Review(String key, boolean value) {
            this.key = key;
            this.value = value;
        }

        boolean expired() {
            return SystemClock.elapsedRealtime() - issuedAt >= REVIEW_LIFETIME_MS;
        }
    }

    private final Map<String, Review> reviews = new HashMap<>();

    List<MediaItem> open(AndroidAutoSettingsTab.Source source, String id, Runnable onChange) {
        var settings = AndroidAutoSettingsTab.load(source);
        if (id.startsWith(ROW_PREFIX)) {
            String key = id.substring(ROW_PREFIX.length());
            var setting = settings.get(key);
            if (setting == null || setting.listener() == null) return Collections.emptyList();
            boolean target = !setting.checked();
            Review review = reviews.get(key);
            if (review == null || review.expired() || review.used || review.value != target) {
                review = new Review(key, target);
                reviews.put(key, review);
            }
            return Collections.singletonList(setting.choice(review.id, target));
        }
        for (Review review : reviews.values()) {
            if (review.expired()) continue;
            var setting = settings.get(review.key);
            if (review.id.equals(id)) {
                if (setting == null || setting.listener() == null || setting.checked() == review.value || review.used) {
                    return Collections.singletonList(AndroidAutoSettingsTab.status(setting));
                }
                // Android Auto can preload settings rows for suggestions. Create the Confirm ID here,
                // one level deeper, so preloading a setting cannot change its value.
                if (review.applyId == null) review.applyId = APPLY_PREFIX + UUID.randomUUID();
                return Collections.singletonList(setting.confirm(review.applyId, review.value));
            }
            if (id.equals(review.applyId)) {
                if (!review.used) {
                    review.used = true;
                    if (setting != null && setting.listener() != null && setting.checked() != review.value) {
                        setting.listener().patch_set(review.value);
                        onChange.run();
                        setting = AndroidAutoSettingsTab.load(source).get(review.key);
                    }
                }
                return Collections.singletonList(AndroidAutoSettingsTab.status(setting));
            }
        }
        return Collections.singletonList(AndroidAutoSettingsTab.expired());
    }
}
