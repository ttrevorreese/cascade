# R8 rules for release builds. Most libraries ship their own (Room, Hilt, Media3, ML Kit,
# kotlinx.serialization, Play Billing, and the Spotify App Remote AAR's proguard.txt).

# Readable stack traces in Play's crash reports; Play deobfuscates them with the uploaded mapping.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The Spotify SDK maps its protocol types with Gson by reflection (its own rules keep the types).
-keep class com.spotify.protocol.** { *; }
-dontwarn com.spotify.**
