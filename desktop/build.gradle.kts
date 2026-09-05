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

    // The DSP behind the visualiser is ordinary maths, and ordinary maths is worth checking: an FFT
    // that is subtly wrong still produces bars that move.
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

/**
 * `gradlew :desktop:run` - the desktop client itself.
 *
 * The heap is capped, and that is worth explaining rather than tuning silently. A JVM left alone
 * sizes its maximum heap at a quarter of physical RAM and then keeps whatever it has claimed, so on
 * a large machine a small app reports a startling number that is mostly unused reservation. Capping
 * it makes the process honest about what it needs, and this one needs very little: a song is a few
 * megabytes and the cover cache is bounded.
 *
 * SerialGC because the alternative is worse here for the same reason - the throughput collectors
 * run several threads and hold larger structures, which buys nothing for a heap this size and shows
 * up directly in the footprint.
 *
 * Note that running through Gradle is itself the larger cost: the Gradle and Kotlin daemons account
 * for several times what the app uses, and none of that exists when a built jar is run directly.
 */
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the OuterTune desktop client."
    mainClass.set("com.dd3boh.outertune.desktop.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx320m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=192m")
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

/**
 * `gradlew :desktop:sizeReport` - what the shipped app would actually weigh.
 *
 * Worth being able to ask rather than estimate. Compose Desktop's floor is Skiko, its native
 * renderer, and that is per-platform: a Windows build carries the Windows one and nothing else,
 * which is the single biggest line and the one that cannot be optimised away while the UI is
 * Compose.
 */
tasks.register("sizeReport") {
    group = "verification"
    description = "Totals the runtime classpath, largest first."
    val classpath = sourceSets["main"].runtimeClasspath
    doLast {
        val jars = classpath.files.filter { it.isFile }.sortedByDescending { it.length() }
        val total = jars.sumOf { it.length() }
        println("=== runtime classpath: ${jars.size} jars, %.1f MB total ===".format(total / 1048576.0))
        jars.take(12).forEach { println("  %7.1f MB  %s".format(it.length() / 1048576.0, it.name)) }
        val rest = jars.drop(12)
        if (rest.isNotEmpty()) {
            println("  %7.1f MB  (%d more)".format(rest.sumOf { it.length() } / 1048576.0, rest.size))
        }
    }
}

/**
 * `gradlew :desktop:fatJar` - one runnable jar, for handing to someone who just wants to run it.
 *
 * Everything on the runtime classpath is unpacked into it, Skiko's native library included, so it
 * needs nothing but a JRE 21. Signature files from the dependencies are excluded: several ship
 * signed jars, and a merged jar carrying their signatures fails verification at startup with an
 * error that says nothing about the real cause.
 *
 * Deliberately not committed to the repository. It is around 45MB of build output that changes with
 * every commit, and git keeps every version of it forever.
 */
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a single runnable jar (java -jar outertune-desktop.jar)."
    archiveFileName.set("outertune-desktop.jar")
    manifest { attributes("Main-Class" to "com.dd3boh.outertune.desktop.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}
