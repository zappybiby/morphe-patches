/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package android.support.v4.media;

public class MediaBrowserCompat {
    public static class MediaItem {
        public static final int FLAG_PLAYABLE = 2;

        public MediaItem(MediaDescriptionCompat description, int flags) {
            throw new UnsupportedOperationException("Stub");
        }
    }
}
