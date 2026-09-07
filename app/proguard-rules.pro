# Release builds strip debug/verbose logging so no BLE payload or credential can
# reach logcat in a shipped build (00-design.md §8.8).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink (via androidx.security:security-crypto) references ErrorProne's
# annotations, which are compile-only and absent at runtime. R8 refuses to
# build with missing classes unless told they are expected; these are the
# exact rules it generated (app/build/outputs/mapping/release/missing_rules.txt).
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
