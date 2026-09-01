/*
 * The desktop client, and the probes that established it was possible.
 *
 * Plain kotlin("jvm") plus the Compose *compiler* plugin, rather than the Compose Multiplatform
 * Gradle plugin. That plugin mostly supplies a `compose.desktop` DSL and dependency aliases, and
 * its releases track a Kotlin version behind the one this project builds with - taking the
 * artifacts directly avoids that skew entirely. The compiler plugin doing the real work is the same
 * one :app already uses.
 */
plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":innertube"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Playback, proven by the probes below: a pure-Java demux/decode chain, so the desktop build
    // has no native audio dependency to bundle.
    implementation("com.tianscar.javasound:jaad:0.9.4")
    implementation("org.mp4parser:isoparser:1.9.56")

    // Compose Multiplatform for desktop. desktop-jvm pulls runtime, foundation, ui and material3.
    implementation("org.jetbrains.compose.desktop:desktop-jvm:1.8.2")
    implementation("org.jetbrains.compose.material3:material3:1.8.2")
    // The Skiko native renderer. Normally the Compose Multiplatform Gradle plugin picks the right
    // one for the host; without that plugin it has to be named, or the window opens and then dies
    // on a missing skiko-windows-x64.dll. Pinned to the version desktop-jvm 1.8.2 resolves.
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4.2")
}

/** `gradlew :desktop:run` - the desktop client itself. */
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the OuterTune desktop client."
    mainClass.set("com.dd3boh.outertune.desktop.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** `gradlew :desktop:probe --args="<videoId>"` - how far does a desktop build get unaided? */
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

/** `gradlew :desktop:fmp4Probe --args="<videoId>"` - can pure Java demux what YouTube serves? */
tasks.register<JavaExec>("fmp4Probe") {
    group = "verification"
    description = "Demuxes fragmented MP4 and decodes it, both in pure Java, end to end."
    mainClass.set("com.dd3boh.outertune.desktop.FragmentedMp4Probe")
    classpath = sourceSets["main"].runtimeClasspath
}
