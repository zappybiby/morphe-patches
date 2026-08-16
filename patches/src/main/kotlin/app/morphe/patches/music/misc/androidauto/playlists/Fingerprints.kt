/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object AndroidAutoMediaIdValidationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    strings = listOf("Invalid media id: ")
)

internal object BrowseRequestBuilderFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    strings = listOf("FEmusic_home"),
    // YTM 9.15/9.29/9.30/9.31: FEmusic_home also appears in an Object-returning lambda.
    // Exclude that method so this fingerprint selects the Browse request builder.
    custom = { method, _ -> method.returnType != "Ljava/lang/Object;" }
)
