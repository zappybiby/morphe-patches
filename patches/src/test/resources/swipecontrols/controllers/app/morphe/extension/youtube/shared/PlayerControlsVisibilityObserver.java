/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.shared;

import android.app.Activity;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;

public final class PlayerControlsVisibilityObserver {
    private final SwipeControlsHostActivity host;
    public PlayerControlsVisibilityObserver(Activity activity) { host = (SwipeControlsHostActivity) activity; }
    public boolean arePlayerControlsVisible() { return host.controlsVisible; }
}
