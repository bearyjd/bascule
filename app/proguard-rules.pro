# Release builds strip debug/verbose logging so no BLE payload or credential can
# reach logcat in a shipped build (00-design.md §8.8).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
