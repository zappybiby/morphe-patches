import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.compile.JavaCompile

dependencies {
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))
    compileOnly(libs.annotation)
    compileOnly(libs.protobuf.javalite)
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
        "app/morphe/extension/music/patches/RestoreAndroidAutoPlaylistsPatch.java",
    )
}
