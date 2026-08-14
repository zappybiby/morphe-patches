/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto.playlists

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.forEachChildElement
import app.morphe.util.getNode
import app.morphe.util.inputStreamFromBundledResource

private const val ADD_ON_PREFERENCES_FILE_PATH = "morphe_addon_prefs.xml"
private const val PLAYLIST_LIST_VIEW_KEY = "morphe_music_android_auto_playlist_list_view"
private const val MUSIC_MISC_SCREEN_KEY = "morphe_settings_music_screen_9_misc"

context(context: ResourcePatchContext)
private fun addPlaylistStrings() {
    val destinationPath = "res/values/strings.xml"
    val destinationFile = context[destinationPath]
    if (!destinationFile.exists()) {
        destinationFile.parentFile?.mkdirs()
        destinationFile.writeText("<resources />")
    }

    val sourceStream = inputStreamFromBundledResource(
        "addresources",
        "values/music/strings.xml",
    ) ?: error("Could not find Android Auto playlist strings")

    sourceStream.use { stream ->
        context.document(destinationPath).use { destinationDocument ->
            val destinationResources = destinationDocument.getNode("resources")
            val existingNames = buildSet {
                destinationResources.forEachChildElement { element ->
                    element.getAttribute("name")
                        .takeIf { name -> name.isNotEmpty() }
                        ?.let { name -> add(name) }
                }
            }

            context.document(stream).use { sourceDocument ->
                sourceDocument.getNode("resources").forEachChildElement { element ->
                    if (element.getAttribute("name") !in existingNames) {
                        destinationResources.appendChild(
                            destinationDocument.importNode(element, true),
                        )
                    }
                }
            }
        }
    }
}

context(context: ResourcePatchContext)
private fun declarePlaylistSetting() {
    val declarationFile = context[ADD_ON_PREFERENCES_FILE_PATH]
    if (!declarationFile.exists()) {
        declarationFile.parentFile?.mkdirs()
        declarationFile.writeText(
            """
                <?xml version="1.0" encoding="utf-8"?>
                <morphe-add-on-preferences xmlns:android="http://schemas.android.com/apk/res/android">
                </morphe-add-on-preferences>
            """.trimIndent(),
        )
    }

    context.document(ADD_ON_PREFERENCES_FILE_PATH).use { document ->
        val declarations = document.getNode("morphe-add-on-preferences")
        var alreadyDeclared = false
        declarations.forEachChildElement { screen ->
            screen.forEachChildElement { preference ->
                if (preference.getAttribute("android:key") == PLAYLIST_LIST_VIEW_KEY) {
                    alreadyDeclared = true
                }
            }
        }
        if (alreadyDeclared) return

        val screen = document.createElement("screen").apply {
            setAttribute("key", MUSIC_MISC_SCREEN_KEY)
        }
        screen.appendChild(document.createElement("SwitchPreference").apply {
            setAttribute("android:key", PLAYLIST_LIST_VIEW_KEY)
            setAttribute("android:title", "@string/${PLAYLIST_LIST_VIEW_KEY}_title")
            setAttribute("android:summary", "@string/${PLAYLIST_LIST_VIEW_KEY}_summary")
        })
        declarations.appendChild(screen)
    }
}

val standaloneSettingsPatch = resourcePatch {
    execute {
        addPlaylistStrings()
        declarePlaylistSetting()
    }
}
