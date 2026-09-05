import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Identity of the source this binary was built from.
//
// Deliberately NOT a wall clock. buildConfigField is evaluated during the
// configuration phase, so a clock-based stamp has two failure modes: Gradle's
// configuration cache freezes it, after which the app reports a build time
// older than its own binary - which is precisely how a stale install goes
// unnoticed - and because it changes on every single build it also defeats the
// up-to-date check on generateBuildConfig and every compile task downstream.
//
// A git-derived stamp is a function of the source instead of the clock: correct
// by construction, and stable across rebuilds of the same commit. The commit
// hash is the part that matters - it is checkable against the repo, so a build
// that did not come from where you think it did is obvious rather than
// plausible. Falls back to "nogit" when built from a tarball with no git.
fun gitOutput(vararg args: String): String = try {
    val proc = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val text = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0) text else ""
} catch (e: Exception) {
    ""
}

val gitSha = gitOutput("rev-parse", "--short=9", "HEAD").ifEmpty { "nogit" } +
    if (gitOutput("status", "--porcelain").isNotEmpty()) "+dirty" else ""
val gitCommitDate = gitOutput("log", "-1", "--format=%cd", "--date=format:%Y-%m-%d %H:%M:%S")
    .ifEmpty { "unknown" }

// Signing credentials, resolved from OUTSIDE the repo.
//
// keystore.properties is gitignored, so when C:\temp\src was deleted and
// re-cloned it went with the directory and the upload key looked lost - it
// survived only as a deleted file in the Recycle Bin. Credentials must live
// somewhere the repo's own lifecycle cannot reach.
//
// The path is per-machine configuration, so it is NOT hardcoded here. That is
// the same mistake gradle.properties documents backing out of, when an absolute
// Windows java home in a tracked file stopped every other host before it could
// read a single build script. Point at yours from your own
// ~/.gradle/gradle.properties (%USERPROFILE%\.gradle\gradle.properties):
//
//     cpmdroidKeystoreProperties=C:/aw/keys/cpmdroid-keystore.properties
//
// or pass -PcpmdroidKeystoreProperties=<path>, or set
// CPMDROID_KEYSTORE_PROPERTIES in the environment. First one that exists wins.
// A checkout that still keeps keystore.properties in the repo root goes on
// working. When nothing resolves, release builds come out unsigned - run
// :app:signingReport before a release and check the alias and SHA-256 rather
// than trusting that a build succeeding means it was signed.
val keystorePropertiesFile = listOfNotNull(
    findProperty("cpmdroidKeystoreProperties") as String?,
    System.getenv("CPMDROID_KEYSTORE_PROPERTIES"),
).map { file(it) }.firstOrNull { it.exists() }
    ?: rootProject.file("keystore.properties")

val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.awohl.cpmdroid"
    compileSdk = 36
    ndkVersion = "28.0.13004108"

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.awohl.cpmdroid"
        minSdk = 24
        targetSdk = 36
        // 27 / "1.25" are spent: Play has seen them, and it refuses an upload at
        // a versionCode it has seen, so the tree could not be uploaded at all
        // while it still said 27. The catalog repin in 642b3b0 and everything
        // since - the v0 disk-name migration included - has shipped to nobody
        // and needs a number of its own.
        //
        // 28 / "1.26" is the disk-name migration (release A). 29 / "1.27" is the
        // catalog repoint and the RomWBW release picker (releases B and C).
        // They are numbered separately even if they are uploaded together,
        // because the ordering is what makes the pair safe: the rename runs in
        // MainActivity.onCreate before anything fetches a catalog, so a device
        // that arrives at 1.27 without ever running 1.26 still renames its
        // files before it can download a v0 name beside a pre-v0 one.
        //
        // 30 / "1.28" is the ROM fetched from the catalog. It gets its own
        // number even though 1.27 has been uploaded to nobody and could have
        // absorbed it. Two reasons, and neither is ordering this time: 1.27's
        // changelog entry says in as many words that no ROM is downloaded and
        // that selecting another release boots with a mismatch warning, and
        // folding this in would leave that entry describing a build that does
        // the opposite; and MANUAL_CHECKS.md files its checks per release, so a
        // build that fetches ROMs is a different thing to point at a device.
        // A versionCode costs nothing. A record that says the wrong thing does.
        versionCode = 30
        versionName = "1.28"

        // Source identity - see the gitOutput() comment above for why this is a
        // commit rather than a clock. SOURCE_DATE is the commit's date, not the
        // moment the compiler ran; when the binary was actually written is read
        // from the installed APK at runtime, where it cannot go stale.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "SOURCE_DATE", "\"$gitCommitDate\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Local JVM tests only - `./gradlew :app:test`. app/src/test holds three
    // suites - the v0 disk-name migration, the catalog parsers, and the JNI
    // name parity between EmulatorEngine.kt and emu_io_android.cpp - and none
    // of them touches an Android class. The first renames the user's images and
    // rewrites the preferences naming them, the second reads documents
    // published by another repository, and the third guards the one mismatch in
    // this project that fails nowhere but at runtime on a device. Nothing here
    // is instrumented and nothing here needs an emulator.
    testImplementation("junit:junit:4.13.2")

    // The REAL org.json, for unit tests only. android.jar's org.json is a stub
    // whose every method throws "not mocked", and the mockable android.jar is
    // last on the unit-test classpath, so this shadows it and the catalog
    // parsers run for real off the mockable stub. Note what is deliberately NOT
    // set anywhere in this file: testOptions.unitTests.isReturnDefaultValues.
    // With it on, losing this dependency would make every JSONObject call return
    // an empty default and the parser tests would pass while parsing nothing.
    testImplementation("org.json:json:20231013")
}
