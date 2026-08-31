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

// Load keystore properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
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
        versionCode = 24
        versionName = "1.23"

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
}
