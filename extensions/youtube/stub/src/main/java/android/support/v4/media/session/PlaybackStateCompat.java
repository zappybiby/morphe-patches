/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package android.support.v4.media.session;

import android.os.Bundle;

import java.util.List;

// Used only while compiling; YouTube Music provides this class with obfuscated fields at runtime.
// Fields a-k match the constructor's parameter order; f and g hold the error code and message.
public final class PlaybackStateCompat {
    public final int a;
    public final long b;
    public final long c;
    public final float d;
    public final long e;
    public final int f;
    public final CharSequence g;
    public final long h;
    public final List<?> i;
    public final long j;
    public final Bundle k;

    public PlaybackStateCompat(
            int state, long position, long bufferedPosition, float speed, long actions,
            int errorCode, CharSequence errorMessage, long updateTime, List<?> customActions,
            long activeQueueId, Bundle extras) {
        throw new UnsupportedOperationException("Stub");
    }
}
