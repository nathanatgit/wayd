# Release shrinking rules. Library consumer rules (SQLCipher, LiteRT-LM, Room, Hilt) come with their modules.

# JNI: the Rust library looks these methods up by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.nathanhanapps.wayd.capture.NativeNet { *; }

# Shizuku starts the privileged service by class name and talks to it through AIDL.
-keep class com.nathanhanapps.wayd.privilege.** { *; }

# Room entities and enums are read by name when exporting and migrating.
-keepclassmembers enum com.nathanhanapps.wayd.** { *; }

# LiteRT-LM reads Kotlin metadata for its configuration classes.
-keep class kotlin.Metadata { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
