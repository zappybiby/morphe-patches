import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.compile.JavaCompile

dependencies {
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))
    compileOnly(libs.annotation)
}

configure<ApplicationExtension> {
    defaultConfig {
        minSdk = 26
    }

    sourceSets.named("main") {
        java.directories.clear()
        java.directories.add(rootProject.file("extensions/music/src/main/java").absolutePath)
    }
}

tasks.withType<JavaCompile>().configureEach {
    include(
        "app/morphe/extension/music/patches/AndroidAutoPlaylistSettings.java",
        "app/morphe/extension/music/patches/PlaylistPageMapper.java",
        "app/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch.java",
    )
}
