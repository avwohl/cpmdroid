# ProGuard rules for CPMDroid

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep EmulatorEngine callbacks
-keep class com.romwbw.cpmdroid.EmulatorEngine {
    void onOutput(byte[]);
}
