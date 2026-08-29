@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties


plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.dd3boh.outertune"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dd3boh.outertune"
        minSdk = 24
        targetSdk = 36
        versionCode = 86
        versionName = "0.10.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // expose the TagLib library version (from the version catalog) for the About screen
        buildConfigField("String", "TAGLIB_VERSION", "\"${libs.versions.taglib.get()}\"")
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("ot_release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                (keystoreProperties["keyAlias"] as? String)?.let {
                    keyAlias = it
                }
                (keystoreProperties["keyPassword"] as? String)?.let {
                    keyPassword = it
                }
                (keystoreProperties["storePassword"] as? String)?.let {
                    storePassword = it
                }
            }
        } else {
            create("ot_release") { }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("ot_release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        // userdebug is release builds without minify
        create("userdebug") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // Lets `adb install -d` downgrade over an existing userdebug install: since Android 14,
            // that flag only takes effect if the *currently installed* APK is debuggable - a plain
            // release build refuses a downgrade install no matter what adb flags are passed. Keeps
            // the release signing config (see initWith above) so this stays interchangeable with a
            // real release build, it's just also debuggable.
            isDebuggable = true
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

// build variants and stuff
    splits {
        abi {
            isEnable = true
            reset()

            include("x86_64", "x86", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    flavorDimensions.add("abi")

    productFlavors {
        // main version
        create("core") {
            isDefault = true
            dimension = "abi"
        }

        // fully featured version, large file size
        create("full") {
            dimension = "abi"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "OuterTune-${variant.versionName}-${output.baseName}-${output.versionCode}.apk"
                output.outputFileName = outputFileName
            }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")

        }
    }

    tasks.withType<KotlinCompile> {
        // Tag extraction uses TagLib in every flavor. Only the FFmpeg playback decoder
        // (nextlib) is flavor-gated: the "full" flavor links it from the prebuilt AAR,
        // while other flavors compile the dud stub.
        if (name.substringAfter("compile").lowercase().startsWith("full")) {
            exclude("**/*ffdecoderDud.kt")
        }
    }


    aboutLibraries {
        offlineMode = true

        collect {
            fetchRemoteLicense = false
            fetchRemoteFunding = false
            filterVariants.addAll("release")
        }

        export {
            // Remove the "generated" timestamp to allow for reproducible builds
            excludeFields = listOf("generated")
        }

        license {
            // Define the strict mode, will fail if the project uses licenses not allowed
            strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
            // Allowed set of licenses, this project will be able to use without build failure
            allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause", "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1", "GNU GENERAL PUBLIC LICENSE, Version 3", "GPL-3.0-only", "GPL-3.0-or-later", "EPL-2.0", "MIT", "MPL-2.0", "Public Domain")

            // Full license text for license IDs mentioned here will be included, even if no detected dependency uses them.
             additionalLicenses.addAll("apache_2_0", "gpl_2_1") // ffMpeg in ffMetadataEx
        }

        library {
            duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
            duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
        }
    }

    // for RB
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        lintConfig = file("lint.xml")
    }

    androidResources {
        generateLocaleConfig = true

        // Ship only the languages asked for, via -Plocales=en,ja (or the workflow's "locales"
        // input). Left unset the build keeps all 50, which is the right default for a release
        // anyone might install; a build made for one person is carrying ~1.4MB of strings that
        // person cannot read.
        //
        // English is always kept whatever is asked for. It is the base values/ resource, so
        // dropping it would leave any string a filtered language happens not to translate with
        // nothing to fall back to.
        val requestedLocales = (project.findProperty("locales") as String?)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        if (requestedLocales.isNotEmpty()) {
            localeFilters += (requestedLocales + "en").distinct()
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Used by the ported cipher/potoken stack, which logs through it at ~300 call sites. Kept as
    // Timber rather than rewritten onto android.util.Log so those files stay diffable against the
    // Metrolist source they came from - the whole point of taking that engine wholesale is being
    // able to compare it line for line when YouTube next changes something.
    implementation(libs.timber)
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    // compose
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)
    implementation(libs.compose.icons.extended)

    // ui
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.shimmer)

    // material
    implementation(libs.adaptive)
    implementation(libs.material3)
    implementation(libs.palette)
    implementation(libs.material.color.utilities)

    // viewmodel
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.media3)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.workmanager)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // Schedules automatic backups outside the playback service, on the system's own background
    // job infra - see AutoBackupWorker.
    implementation(libs.work.runtime.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.serialization.json)

    // modules
    implementation(project(":innertube"))
    // MetrolistGroup:innertubex is gone with the resolver that used it. Nothing in this app links
    // against it any more - the engine imported from Metrolist v13.6.3 does its own deobfuscation
    // and never touched that library, which is the whole reason it was taken - so keeping the
    // dependency would only be paying its weight in the apk for classes nothing calls.
    // Ktor stays on the app classpath: :innertube keeps these implementation-scoped rather than
    // exposing them.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":betterlyrics"))
    implementation(project(":simpmusic"))
    implementation(project(":kizzy"))
    implementation(project(":shazamkit"))

    // misc
    implementation(libs.aboutlibraries.compose.m3)

    // local metadata tag extraction (TagLib, all flavors)
    implementation(libs.taglib)

    // sdk24 support
    // Support for N is officially unsupported even it the app should still work. Leave this outside of the version catalog.
    implementation("androidx.webkit:webkit:1.14.0")

    testImplementation(libs.junit)
}

afterEvaluate {
    dependencies {
        add("fullImplementation", files("../prebuilt/ffMetadataEx-release.aar"))
    }
}
