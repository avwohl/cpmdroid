# ProGuard rules for CPMDroid

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep EmulatorEngine callbacks
-keep class com.awohl.cpmdroid.EmulatorEngine {
    void onOutput(byte[]);
}
