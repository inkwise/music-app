# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn dagger.hilt.**

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.**

# Retrofit + OkHttp
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }
-keepattributes SerializedName

# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# MMKV
-keep class com.tencent.mmkv.** { *; }
-dontwarn com.tencent.mmkv.**

# Jaudiotagger
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# Palette
-keep class androidx.palette.** { *; }

# Navigation Compose
-keep class androidx.navigation.** { *; }

# Keep data classes used in serialization
-keep class com.inkwise.music.**.model.** { *; }
-keep class com.inkwise.music.**.data.** { *; }
-keep class com.inkwise.music.**.entity.** { *; }

# BASS audio library - JNI native code accesses Java fields/methods by name
-keep class com.un4seen.bass.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
