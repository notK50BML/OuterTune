/*
 * A plain JVM spike, deliberately not an application.
 *
 * The point of this module is to establish, against the real engine rather than from reasoning,
 * exactly how far a desktop build can get before it needs a browser. :innertube is already
 * kotlin("jvm") with no Android imports, so the whole API layer is expected to work here
 * untouched; what cannot work yet is anything that needs BotGuard, because that runs in a WebView.
 */
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":innertube"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Pure-Java AAC decoder. If this resolves and decodes, a desktop build needs no native audio
    // library at all - see AudioDecodeProbe.
    implementation("com.tianscar.javasound:jaad:0.9.4")
}

/** So the probe can be run straight from Gradle: `gradlew :desktop:probe --args="<videoId>"`. */
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Reports how far a desktop build gets before it needs a browser engine."
    mainClass.set("com.dd3boh.outertune.desktop.StackProbe")
    classpath = sourceSets["main"].runtimeClasspath
}

/** `gradlew :desktop:decodeProbe --args="<videoId>"` - does desktop playback need native code? */
tasks.register<JavaExec>("decodeProbe") {
    group = "verification"
    description = "Fetches AAC audio and decodes it in pure Java, to decide if a native audio library is needed."
    mainClass.set("com.dd3boh.outertune.desktop.AudioDecodeProbe")
    classpath = sourceSets["main"].runtimeClasspath
}
