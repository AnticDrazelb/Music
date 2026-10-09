# Keep stack traces readable; the size win from obfuscation is small here.
-dontobfuscate

# NewPipeExtractor and its runtime dependencies rely on reflection
# (Rhino's JS engine, timeago patterns, protobuf-lite messages).
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# App models are (de)serialized; keep them intact.
-keep class app.tempo.music.data.** { *; }
-keepclassmembers class app.tempo.music.** {
    *** Companion;
}
-keepclasseswithmembers class app.tempo.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The Cast framework creates the options provider named in the manifest by reflection.
-keep class app.tempo.music.playback.cast.CastOptionsProvider { *; }
