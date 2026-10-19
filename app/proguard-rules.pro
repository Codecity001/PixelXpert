# ProGuard/R8 rules for the PixelXpert module.
#
# This is an Xposed module: LSPosed loads it by name from
# META-INF/xposed/java_init.list, and the mod packs hook other processes'
# internals reflectively. Anything R8 renames or strips can break that
# silently at runtime rather than at build time.

# ---------------------------------------------------------------------------
# Xposed entry point and mod packs
# ---------------------------------------------------------------------------

# The entry point is loaded reflectively by the framework via
# META-INF/xposed/java_init.list - it must never be renamed or stripped.
-keep class sh.siava.pixelxpert.xposed.XPLauncher { *; }
-keep class * extends sh.siava.pixelxpert.xposed.XposedModPack { *; }

# The generated registry (ModPacks) holds ::class.java references to every
# pack and is read by XPLauncher. Keep the registry and its entry shape.
-keep class sh.siava.pixelxpert.xposed.ModPacks { *; }
-keep class sh.siava.pixelxpert.annotations.ModPackData { *; }

# Whole xposed tree: packs reach framework and SystemUI internals by name.
-keep class sh.siava.pixelxpert.xposed.** { *; }

# Whole package for debug and trace
-keep class sh.siava.pixelxpert.** { public protected private *; }
-keep class sh.siava.pixelxpert.**$* { public protected private *; }

# ---------------------------------------------------------------------------
# libxposed API - provided at runtime by the framework, never packaged here
# ---------------------------------------------------------------------------
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# Legacy Xposed helpers referenced from the packs
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# ---------------------------------------------------------------------------
# AIDL - generated stubs are referenced across process boundaries
# ---------------------------------------------------------------------------
-keep interface **.I* { *; }
-keep class **.I*$Stub { *; }
-keep class **.I*$Stub$Proxy { *; }
-keep class sh.siava.pixelxpert.IPixelXpertProxy { *; }
-keep class sh.siava.pixelxpert.IPixelXpertProxy$Stub { *; }
-keep class sh.siava.pixelxpert.IRootProviderService { *; }
-keep class sh.siava.pixelxpert.IRootProviderService$Stub { *; }
-keep class sh.siava.pixelxpert.service.** { *; }

# ---------------------------------------------------------------------------
# Kotlin runtime - strip intrinsics checks
# ---------------------------------------------------------------------------
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}

# Strip debug log
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ---------------------------------------------------------------------------
# AndroidX & Window Extensions
# Optional / vendor-provided classes referenced by androidx.window.
# These are supplied by the device rather than packaged here, so R8 can never
# resolve them. Suppressing the warning is the documented remedy.
# ---------------------------------------------------------------------------
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-dontwarn javax.annotation.**
-keepnames class androidx.compose.ui.**
-keep,allowoptimization,allowobfuscation class androidx.constraintlayout.motion.widget.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.recyclerview.widget.** { *; }

# ---------------------------------------------------------------------------
# Parcelable Creators & Attributes
# ---------------------------------------------------------------------------
-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,*Annotation*,EnclosingMethod,SourceFile,LineNumberTable

# Keep all native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all annotation types
-keep @interface ** { *; }

# ---------------------------------------------------------------------------
# Lottie
# ---------------------------------------------------------------------------
-dontwarn com.airbnb.lottie.**
-keep class com.airbnb.lottie.** { *; }

# ---------------------------------------------------------------------------
# Debug hook audit data models for Gson serialization/deserialization
# ---------------------------------------------------------------------------
-keep class sh.siava.pixelxpert.ui.debug.model.** { *; }
-keepclassmembers class sh.siava.pixelxpert.ui.debug.model.** { *; }
-keep class androidx.core.content.FileProvider { *; }