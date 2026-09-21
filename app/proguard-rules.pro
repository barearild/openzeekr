# ============================================================================
# OpenZeekr :app - RELEASE keep rules (R8 minify + resource shrinking).
#
# These rules apply ONLY to the release build (isMinifyEnabled/isShrinkResources
# in build.gradle.kts). They shrink + obfuscate code and strip unused resources.
#
# IMPORTANT: R8 does NOT encrypt string constants. Any baked BuildConfig secret
# (SecretsConfig / BuildConfig.SEC_*) is still recoverable from the DEX by anyone
# who unpacks the APK. This is code obfuscation/shrinking only, NOT secret hiding.
# Do not rely on R8 to protect keys - that is what the clean-repo (unbaked) build
# and EncryptedSharedPreferences are for.
#
# The :core module is compiled INTO this app (serialization, Retrofit, OkHttp,
# okio, BouncyCastle, Tink) so all of core's runtime-reflection surfaces must be
# kept here as well.
# ============================================================================

# ---- Kotlin metadata / attributes needed by reflection-based libs ----
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions
-keepattributes *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes RuntimeVisibleTypeAnnotations, RuntimeInvisibleAnnotations

# ============================================================================
# kotlinx.serialization  (official R8 rules)
# Every @Serializable data class in :core (net/model/Models.kt, config/SecretsConfig,
# and the DK request/response beans in DkProvisioning) is (de)serialized reflectively
# via its generated $$serializer / Companion, so those must survive shrinking + renaming.
# ============================================================================
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep the generated serializer for every @Serializable class.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Serializable `object`s: keep INSTANCE + serializer().
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep members annotated @Serializable and the generated *$$serializer classes.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
    <fields>;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}

# ---- Enums used by (de)serialization: keep values()/valueOf() ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# Retrofit  (official R8 rules)
# TspApi / DkApi are interfaces whose @GET/@POST methods are read by reflection.
# ============================================================================
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Retrofit
-dontwarn retrofit2.**
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# ============================================================================
# OkHttp + okio
# ============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================================
# BouncyCastle (:core DK crypto: CSR/cert, ECDSA, AES) - uses SPI + reflection.
# ============================================================================
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ============================================================================
# Tink / androidx.security.crypto (EncryptedSharedPreferences in ConfigStore).
# Tink resolves key managers reflectively + via protobuf; stripping it breaks the
# encrypted config store on first launch.
# ============================================================================
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ============================================================================
# Kotlin coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ============================================================================
# JNI: keep any class that declares native methods, and native method names, so
# a native<->JVM name binding is never broken. NativeSecrets (libozsecrets.so) binds
# by the fully-qualified name Java_com_openzeekr_app_util_NativeSecrets_*, so its CLASS
# name and its native method names must survive both shrinking and obfuscation.
# ============================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.openzeekr.app.util.NativeSecrets {
    native <methods>;
}

# ============================================================================
# MapLibre GL (native renderer via JNI + reflection) - parked-car map.
# ============================================================================
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# ============================================================================
# Play Services Wear Data Layer (key clone to the watch) - has consumer rules,
# dontwarn as a safety net.
# ============================================================================
-dontwarn com.google.android.gms.**

# ============================================================================
# Third-party native SDKs that MAY appear on the classpath in other build
# flavours (the stock app used these; openzeekr does not bundle them, so these
# are pure defensive dontwarns and are no-ops when the classes are absent).
# ============================================================================
-dontwarn com.here.**
-dontwarn com.unity3d.**
-dontwarn javax.naming.**
