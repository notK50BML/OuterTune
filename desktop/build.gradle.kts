import java.util.zip.ZipFile

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
    // Both are kotlin("jvm") modules built on ktor, the same as everything else here, so the
    // desktop uses them as they are. Nothing about lyrics needed porting.
    implementation(project(":lrclib"))
    implementation(project(":kugou"))
    // Word-by-word timings, and its own TTML parser. Same story as the other two: a plain JVM
    // module, so it is used rather than ported.
    implementation(project(":betterlyrics"))
    // Discord rich presence. Same story again: kotlin("jvm") on ktor, with no Android dependency
    // anywhere in it - the gateway connection it opens is a plain websocket, so the phone and the
    // desktop can use the exact same client.
    implementation(project(":kizzy"))
    // Listen Together's LAN discovery. The phone uses Android's NsdManager for the same DNS-SD
    // browse/advertise this does; jmDNS is the equivalent on a plain JVM, and the only new
    // dependency the whole feature needed - everything else in it was already platform-agnostic.
    implementation("org.jmdns:jmdns:3.5.9")
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Playback, proven by the probes below: a pure-Java demux/decode chain, so the desktop build
    // has no native audio dependency to bundle.
    implementation("com.tianscar.javasound:jaad:0.9.4")
    implementation("org.mp4parser:isoparser:1.9.56")

    // Local music. Decoders as Java Sound plugins - MP3, FLAC, Ogg Vorbis - so a file on disk plays
    // through the same AudioSystem call whatever it is; all pure Java, a few hundred KB together.
    // jaudiotagger reads the tags: title, every credited artist, album, cover art, embedded lyrics.
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4") {
        // jlayer declares JUnit as a runtime dependency; nothing at runtime uses it.
        exclude(group = "junit")
    }
    implementation("org.jflac:jflac-codec:1.5.2")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Windows' media controls (WindowsMediaControls): media keys when unfocused, and the song in
    // the volume flyout and on the lock screen. JNA calls straight into combase.dll; no other
    // native code is shipped.
    implementation("net.java.dev.jna:jna:5.15.0")

    // Compose Multiplatform for desktop. desktop-jvm pulls runtime, foundation, ui and material3.
    implementation("org.jetbrains.compose.desktop:desktop-jvm:1.8.2")
    implementation("org.jetbrains.compose.material3:material3:1.8.2")
    // The Skiko native renderer. Normally the Compose Multiplatform Gradle plugin picks the right
    // one for the host; without that plugin it has to be named, or the window opens and then dies
    // on a missing skiko-windows-x64.dll. Pinned to the version desktop-jvm 1.8.2 resolves.
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.4.2")

    // SQLite, through the pure-JDBC driver.
    //
    // Chosen over SQLDelight or Room-KMP for the same reason this module takes Compose as artifacts
    // rather than as a Gradle plugin: neither needs a plugin or a code generator to work, and both
    // of those pin versions that drift against the Kotlin this project builds with.
    //
    // SQLite specifically, rather than H2 or HSQLDB, because the Android side is already SQLite -
    // so a schema and a query written here mean the same thing there, which is what would make
    // sharing a data layer possible later rather than a rewrite.
    //
    // The published jar carries native binaries for every platform it supports, which is about 12MB
    // for the eleven that will never run. fatJar keeps only the one for the host - see below.
    implementation("org.xerial:sqlite-jdbc:3.50.1.0")

    // For the DevTools Protocol, which is JSON over a WebSocket. Both halves are already here -
    // OkHttp arrives under ktor-client-okhttp and has a WebSocket client - but they arrive
    // transitively, and a compile-time dependency on something another module happens to pull in is
    // a build that breaks the day that module changes its mind.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // Only the runtime JSON tree is used - parseToJsonElement and JsonObject - so the
    // serialization compiler plugin is not needed here.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // The DSP behind the visualiser is ordinary maths, and ordinary maths is worth checking: an FFT
    // that is subtly wrong still produces bars that move.
    testImplementation("junit:junit:4.13.2")
    // Test-only, for IconGenerator: the exact Material Rounded icons the phone app uses, read once
    // into path strings (GeneratedIconPaths). Never shipped - the library is ~11MB for a few dozen
    // glyphs, which is why the app carries paths instead.
    testImplementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")
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

/** `gradlew :desktop:cdpProbe` - the smallest thing CdpSession must do, step by step. */
tasks.register<JavaExec>("cdpProbe") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dd3boh.outertune.desktop.CdpProbe")
}

/**
 * `gradlew :desktop:clientProbe --args="<videoId>"` - what does each client say today?
 *
 * "No client worked" is the least useful error a player can give, and YouTube changes what it
 * accepts without notice. This turns a guess into a reading.
 */
tasks.register<JavaExec>("clientProbe") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dd3boh.outertune.desktop.ClientProbe")
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
    // Java Sound finds its decoders through META-INF/services files, and several jars ship one of
    // the same name. EXCLUDE keeps only the first, which silently dropped every other decoder -
    // MP3 would play and FLAC would not, or the reverse. These are merged instead, line by line.
    val mergedServices = layout.buildDirectory.dir("merged-services")
    // Captured here, as a file collection, so the action below references nothing from the script
    // itself - which the configuration cache cannot serialise.
    val runtimeJars: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(runtimeJars)
    doFirst {
        val out = mergedServices.get().asFile.resolve("META-INF/services")
        out.deleteRecursively()
        out.mkdirs()
        val merged = sortedMapOf<String, MutableSet<String>>()
        runtimeJars.filter { it.name.endsWith("jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                    .forEach { entry ->
                        val lines = zip.getInputStream(entry).bufferedReader().readLines()
                            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                        merged.getOrPut(entry.name.removePrefix("META-INF/services/")) { linkedSetOf() } += lines
                    }
            }
        }
        merged.forEach { (name, lines) -> out.resolve(name).writeText(lines.joinToString("\n", postfix = "\n")) }
    }
    from(mergedServices)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it).matching { exclude("META-INF/services/**") } }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")

    // sqlite-jdbc ships a native library per platform in one jar - Linux on four architectures,
    // macOS, FreeBSD, Android, and so on. A jar built to be run on Windows carries about 12MB of
    // binaries that cannot execute on it. Keeping only the host's is the single largest saving
    // available here, and it is free.
    //
    // Nothing else in the build looks at these paths, so the risk is confined to this task: if the
    // wrong one were dropped the jar fails loudly at the first query rather than subtly later.
    exclude("org/sqlite/native/Linux/**")
    exclude("org/sqlite/native/Linux-Android/**")
    exclude("org/sqlite/native/Linux-Musl/**")
    exclude("org/sqlite/native/Mac/**")
    exclude("org/sqlite/native/FreeBSD/**")
    // JNA ships its native dispatcher for every platform it supports; only Windows' is ever loaded.
    exclude("com/sun/jna/linux-*/**", "com/sun/jna/darwin*/**", "com/sun/jna/freebsd-*/**",
        "com/sun/jna/openbsd-*/**", "com/sun/jna/sunos-*/**", "com/sun/jna/aix-*/**", "com/sun/jna/dragonflybsd-*/**")
}

/**
 * `gradlew :desktop:packageApp` - OuterTune as a Windows app: a folder holding OuterTune.exe and
 * its own trimmed Java runtime, zipped as build/dist/OuterTune-windows.zip. Nothing needs to be
 * installed to run it - unzip, double-click.
 *
 * jpackage's app-image rather than an installer: an installer needs WiX on the build machine, and
 * a portable folder is what a beta wants anyway. The runtime is cut down to the modules the app
 * uses, which is most of why the result is far smaller than a full JDK.
 */
val jpackageTool = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath }
val appImageInput = layout.buildDirectory.dir("app-input")
// Not build/app-image: a copy run straight from the build folder would lock it against the next build.
val appImageOut = layout.buildDirectory.dir("jpackage")
val appIcon = layout.projectDirectory.file("packaging/OuterTune.ico")

val stageAppInput = tasks.register<Copy>("stageAppInput") {
    from(tasks.named("fatJar"))
    into(appImageInput)
}

val appImage = tasks.register<Exec>("appImage") {
    group = "distribution"
    dependsOn(stageAppInput)
    inputs.dir(appImageInput)
    inputs.file(appIcon)
    outputs.dir(appImageOut)
    val outDir = appImageOut.get().asFile
    val inDir = appImageInput.get().asFile
    val icon = appIcon.asFile
    // jpackage marks what it writes read-only, which a plain recursive delete on Windows gives up on.
    doFirst { outDir.walkBottomUp().forEach { it.setWritable(true); it.delete() } }
    executable(jpackageTool.get())
    args(
        "--type", "app-image",
        "--name", "OuterTune",
        "--app-version", "0.10.15",
        "--vendor", "OuterTune Project",
        "--icon", icon.absolutePath,
        "--input", inDir.absolutePath,
        "--main-jar", "outertune-desktop.jar",
        "--main-class", "com.dd3boh.outertune.desktop.MainKt",
        "--dest", outDir.absolutePath,
        "--add-modules", listOf(
            "java.base", "java.desktop", "java.sql", "java.net.http", "java.naming", "java.logging",
            "java.management", "java.prefs", "java.xml", "java.scripting", "jdk.unsupported",
            "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.charsets", "jdk.zipfs", "jdk.accessibility",
            "jdk.localedata", "jdk.net",
        ).joinToString(","),
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages --strip-native-commands --compress=zip-6",
        "--java-options", "-Xmx320m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m",
    )
}

tasks.register<Zip>("packageApp") {
    group = "distribution"
    description = "Builds OuterTune.exe with a bundled runtime, zipped (build/dist/OuterTune-windows.zip)."
    dependsOn(appImage)
    from(appImageOut)
    archiveFileName.set("OuterTune-windows.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}
