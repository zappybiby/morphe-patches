/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patches.all.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitHook
import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitOnCreateHook

val standaloneExtensionPatch = sharedExtensionPatch(
    listOf("shared-youtube"),
    youTubeMusicApplicationInitHook,
    youTubeMusicApplicationInitOnCreateHook,
)
