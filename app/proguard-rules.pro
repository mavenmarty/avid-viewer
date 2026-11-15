# R8 / ProGuard rules for Avid Viewer.
#
# The app's own code (com.avidviewer.tv.**) IS obfuscated and shrunk — that is the point of
# turning this on. The rules below keep only the third-party libraries that rely on reflection,
# so shrinking never removes something they look up by name at runtime. All of the app's JSON is
# parsed by hand with org.json (no Gson / kotlinx.serialization / reflection), so the model
# classes need no keep rules.

# Keep source file + line numbers so crash reports (emailed to bugs@) still point at a line.
# Class and method names in the app's own code are obfuscated; retrace a stack with the
# mapping.txt that R8 writes to app/build/outputs/mapping/release/ for that build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Needed by some libraries' runtime checks / generics.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ---- SMB backups (smbj + its transitive reflective bits) ----
# smbj instantiates packet factories, SMB dialects and event handlers by reflection, and pulls in
# BouncyCastle as a security provider. Keep these libraries intact rather than risk a stripped class
# surfacing only on a real backup to a network share (which the unit tests exercise, but only on the
# un-minified debug build).
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---- Coil ----
-dontwarn coil.**

# Enums: R8 keeps values()/valueOf for enums by default; this is belt-and-braces in case any enum
# is ever resolved from a stored string.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
