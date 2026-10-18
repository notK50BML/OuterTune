# ProGuard for the Windows build: shrinking only. Nothing is renamed or optimised, so stack traces
# and reflection behave exactly as in the unshrunk jar. Run by `gradlew :desktop:shrunkJar`, which
# also passes -injars/-outjars/-libraryjars and a keep rule for every service implementation.
#
# What is shrunk is what is big and statically linked: Compose (about 39MB raw, of which the app
# uses a fraction), androidx.collection and the Kotlin standard library. Everything else is kept
# whole - the networking, YouTube, JavaScript, database, audio and tag libraries all load classes
# by name somewhere, and finding every such place is not worth the few megabytes they would give.

-dontobfuscate
-dontoptimize
-dontwarn **
-dontnote **
-ignorewarnings
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable,RuntimeVisible*,AnnotationDefault,MethodParameters,KotlinMetadata

-keep class com.dd3boh.outertune.desktop.MainKt { public static void main(java.lang.String[]); }

# Everything outside the shrinkable packages, whole.
-keep class !androidx.compose.**,!androidx.collection.**,!kotlin.**,** { *; }

# Natives look up their Java side by name.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Enum values() / valueOf() are reached reflectively (serialisation, settings).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Compose: the few places it looks things up by name.
-keep class androidx.compose.ui.platform.** { *; }
-keep class androidx.compose.runtime.internal.** { *; }
-keep class androidx.compose.ui.text.platform.** { *; }
-keep class * implements androidx.compose.ui.platform.PlatformContext { *; }

# Kotlin: reflection-adjacent pieces some libraries touch by name.
-keep class kotlin.Metadata { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers class kotlin.** { *** Companion; }
