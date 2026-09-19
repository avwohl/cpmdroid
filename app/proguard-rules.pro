# ProGuard/R8 rules for CPMDroid.
#
# These do nothing until R8 runs, and R8 has run only since 1.33 - see the
# comment on isMinifyEnabled in build.gradle.kts for what asked for it. Before
# that this file was three rules nothing consulted.
#
# The whole of the risk is JNI, and it is a rename risk rather than a deletion
# one. The emulator is reached by DYNAMIC lookup, not RegisterNatives: the
# runtime mangles the package, the class name and the method name into
# Java_com_awohl_cpmdroid_EmulatorEngine_nativeRun and asks libcpmdroid.so for
# that symbol. R8 renames Kotlin. The .so is compiled from emu_io_android.cpp
# and cannot be renamed with it, so a rename on this side is an
# UnsatisfiedLinkError at the first call on a device - which CLAUDE.md names as
# the failure nothing here catches at build time.
#
# verifyJniNamesSurviveR8, in app/build.gradle.kts, reads the mapping file R8
# writes and fails the release build if any of this stopped working. Keep the
# task and these rules together: the task is the only thing that says the rules
# still match anything.

# The native declarations in EmulatorEngine.kt, by name.
#
# proguard-android-optimize.txt already carries this exact rule, and it is
# repeated here deliberately. That file is extracted into build/ by Gradle, so
# a reader looking for what keeps the JNI surface alive will not find it there,
# and a future edit that drops the default file from proguardFiles would take
# the rule with it silently.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The one call that goes the other way, and the one rule nothing else provides.
#
# emu_io_android.cpp does GetMethodID(clazz, "onOutput", "([B)V") on the object
# it was handed, so that name and that descriptor are a contract with C++ in
# exactly the way the native declarations are. Nothing in Kotlin calls
# onOutput - it is marked @Suppress("unused") for that reason - so to R8 it is
# an unreachable public method: without this rule it is not merely renamed, it
# is deleted, GetMethodID answers null, nothing in emu_io_android.cpp checks
# that, and every byte the guest writes reaches CallVoidMethod with a null
# method ID.
-keep class com.awohl.cpmdroid.EmulatorEngine {
    void onOutput(byte[]);
}

# Line numbers, so a crash report from the store retraces to a source line.
#
# The mapping file travels inside the AAB and Play undoes the renaming with it -
# but only of what is still there to undo. Without these two, a stack trace
# arrives with no file and no line, and this is an app whose terminal work is
# largely unwatched (MANUAL_CHECKS.md): a store crash report is some of the
# only evidence there is.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
