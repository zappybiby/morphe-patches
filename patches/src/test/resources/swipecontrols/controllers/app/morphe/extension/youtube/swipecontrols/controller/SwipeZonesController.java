/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.swipecontrols.controller;

import app.morphe.extension.youtube.swipecontrols.misc.Rectangle;

public final class SwipeZonesController {
    public Rectangle left = new Rectangle(0, 0, 300, 800);
    public Rectangle right = new Rectangle(700, 0, 300, 800);
    public Rectangle top = new Rectangle(0, 0, 1000, 200);
    public Rectangle getLeft() { return left; }
    public Rectangle getRight() { return right; }
    public Rectangle getTop() { return top; }
}
