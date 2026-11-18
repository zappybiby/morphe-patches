/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

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

    List<MediaItem> open(AndroidAutoSettingsTab.NativeSettings settings, String id, Runnable onChange) {
        String key = AndroidAutoSettingsTab.itemKey(id);
        if (key != null) {
            Boolean checked = AndroidAutoSettingsTab.value(settings, key);
            if (checked == null) return Collections.emptyList();
            boolean target = !checked;
            Review review = reviews.get(key);
            if (review == null || review.expired() || review.used || review.value != target) {
                review = new Review(key, target);
                reviews.put(key, review);
            }
            return Collections.singletonList(AndroidAutoSettingsTab.choice(review.id, key, target));
        }
        for (Review review : reviews.values()) {
            if (review.expired()) continue;
            if (review.id.equals(id)) {
                Boolean current = AndroidAutoSettingsTab.value(settings, review.key);
                if (current == null || current == review.value || review.used) {
                    return Collections.singletonList(AndroidAutoSettingsTab.status(review.key, current));
                }
                // Issue the final destination only inside this review. The inspected host's
                // suggestions and root preload can read as far as a setting, but never this review.
                if (review.applyId == null) review.applyId = APPLY_PREFIX + UUID.randomUUID();
                return Collections.singletonList(AndroidAutoSettingsTab.confirm(review.applyId, review.key, review.value));
            }
            if (id.equals(review.applyId)) {
                if (!review.used) {
                    review.used = true;
                    Boolean current = AndroidAutoSettingsTab.value(settings, review.key);
                    if (current != null && current != review.value
                            && AndroidAutoSettingsTab.apply(settings, review.key, review.value)) onChange.run();
                }
                return Collections.singletonList(AndroidAutoSettingsTab.status(
                        review.key, AndroidAutoSettingsTab.value(settings, review.key)));
            }
        }
        return Collections.singletonList(AndroidAutoSettingsTab.expired());
    }
}
