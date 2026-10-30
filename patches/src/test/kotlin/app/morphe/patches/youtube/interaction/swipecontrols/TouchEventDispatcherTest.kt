/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.swipecontrols

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class TouchEventDispatcherTest {
    @Test
    fun `tap callbacks retain the original DOWN and cannot duplicate an active start`() =
        withFixture("canonical")

    @Test
    fun `handoff restores surviving pointers including pointer id 31 and reordered indices`() =
        withFixture("pointerRecovery")

    @Test
    fun `false native results still record delivery and legitimate results remain unchanged`() =
        withFixture("receiverResults")

    @Test
    fun `input cancellation is terminal even with a different down time or pointer`() =
        withFixture("cancellation")

    @Test
    fun `explicit cancellation preserves caller cleanup without an active pointer target`() =
        withFixture("explicitCancellation")

    @Test
    fun `nested callbacks cannot revive cancelled or replace newer gestures`() =
        withFixture("reentrancy")

    @Test
    fun `all four step programs preserve the native pointer protocol`() =
        withFixture("finitePrograms")

    private fun withFixture(scenario: String) {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions/youtube/src/main/java").isDirectory }
        val classes = File(root,
            "extensions/youtube/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes")
        val fixtures = File(root, "patches/src/test/resources/swipecontrols/touchdispatcher")
        val output = createTempDirectory("touch-dispatcher-runtime").toFile()
        try {
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", classes.path, "-d", output.path,
                File(fixtures, "MotionEvent.java").path,
                File(fixtures, "TouchDispatcherFixture.java").path))
            URLClassLoader(arrayOf(output.toURI().toURL(), classes.toURI().toURL()), null).use { loader ->
                loader.loadClass("TouchDispatcherFixture")
                    .getMethod(scenario).invoke(null)
            }
        } finally {
            output.deleteRecursively()
        }
    }
}
