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

class SwipeGestureControllerTest {
    @Test
    fun `original touches pass through even when the detector reports handling DOWN`() =
        withFixture("passiveTouches")

    @Test
    fun `accepted swipes cancel native input before actions and retain ownership outside zones`() =
        withFixture("claimedSwipe")

    @Test
    fun `resize and fullscreen transitions cannot claim an ineligible starting touch`() =
        withFixture("unclaimedResize")

    @Test
    fun `native fullscreen changes before the threshold cannot steal a configured swipe`() =
        withFixture("nativeSideEffects")

    @Test
    fun `Classic hands the first extra finger to the player with only a needed DOWN`() =
        withFixture("classicHandoff")

    @Test
    fun `stopping recognition preserves existing native or swipe ownership`() =
        withFixture("gateTransitions")

    @Test
    fun `physical cancellation cannot become a pointer handoff`() =
        withFixture("physicalCancellation")

    @Test
    fun `Press activation requires a new hold after release`() =
        withFixture("pressActivation")

    @Test
    fun `disposal cancels pending recognition and releases the original touch`() =
        withFixture("disposal")

    private fun withFixture(scenario: String) {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions/youtube/src/main/java").isDirectory }
        val classes = File(root,
            "extensions/youtube/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes")
        val fixtures = File(root, "patches/src/test/resources/swipecontrols/controllers")
        val output = createTempDirectory("swipe-controller-runtime").toFile()
        try {
            val sources = fixtures.walkTopDown().filter { it.extension == "java" }.map { it.path }.toList()
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", classes.path, "-d", output.path, *sources.toTypedArray()))
            URLClassLoader(arrayOf(output.toURI().toURL(), classes.toURI().toURL()), null).use { loader ->
                loader.loadClass("SwipeControllerFixture").getMethod(scenario).invoke(null)
            }
        } finally {
            output.deleteRecursively()
        }
    }
}
