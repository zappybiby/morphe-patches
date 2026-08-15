import app.morphe.patches.gradle.PatchesExtension
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

apply<app.morphe.patches.gradle.PatchesPlugin>()

group = "app.morphe"
version = "0.1.5"

extensions.configure<PatchesExtension>("patches") {
    about {
        name = "Browse YouTube Music Android Auto playlists"
        description = "Lets you open YouTube Music playlists and choose individual songs in Android Auto"
        source = "https://github.com/zappybiby/morphe-patches"
        author = "zappybiby"
        contact = "na"
        website = "https://github.com/zappybiby/morphe-patches"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

dependencies {
    add("implementation", libs.guava)
    add("implementation", libs.morphe.patches.library)
    add("compileOnly", project(":patches:stub"))
}

extensions.configure<KotlinJvmProjectExtension>("kotlin") {
    compilerOptions {
        freeCompilerArgs.set(listOf("-Xcontext-parameters"))
    }
    sourceSets {
        named("main") {
            kotlin.srcDir("../patches/src/main/kotlin")
            kotlin.include(
                "app/morphe/patches/music/misc/androidauto/playlists/Fingerprints.kt",
                "app/morphe/patches/music/misc/androidauto/playlists/RestoreAndroidAutoPlaylistsPatch.kt",
                "app/morphe/patches/music/misc/androidauto/playlists/StandaloneExtensionPatch.kt",
                "app/morphe/patches/music/misc/androidauto/playlists/StandaloneSettingsPatch.kt",
                "app/morphe/patches/music/misc/extension/hooks/ApplicationInitHook.kt",
                "app/morphe/patches/music/shared/Constants.kt",
                "app/morphe/patches/music/shared/Fingerprints.kt",
            )
        }
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("browse-android-auto-playlists")
    exclude(
        "extensions/music.mpe",
        "extensions/reddit.mpe",
        "extensions/shared-youtube.mpe",
        "extensions/youtube.mpe",
    )
}

tasks.named("build") {
    dependsOn("buildAndroid")
}
