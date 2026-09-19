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
        // 31 / "1.29" is the release that carries no ROM at all. It gets its
        // own number rather than folding into 1.28 for the reason above: 1.28's
        // entry says the app still bundles a 3.5.1 ROM and falls back to it,
        // and this build does the opposite - assets/emu_avw.rom is gone, every
        // ROM comes from the catalog, the user picks which one, and a fresh or
        // upgrading install follows the index's own default release instead of
        // being pinned to whatever the package happened to carry.
        //
        // 32 / "1.30" is the release that compiles in no cpmdroid URL at all.
        // The help topics were the last thing this app fetched from its own
        // releases area, and they now come out of the romwbw_disks index like
        // every other asset - so a corrected help topic reaches a reader with no
        // app release, and no cpmdroid release has to stay Latest forever to
        // keep an installed build working.
        //
        // 33 / "1.31" is 1.30's code under a number Play has not seen. Nothing
        // in the app changed: this is the build that is actually uploaded,
        // where 28 through 32 were spent on builds that reached nobody.
        //
        // Measured rather than assumed, with tools/check-store-version.sh on
        // 2026-09-10: Play serves 1.25, which CHANGELOG.md maps to versionCode
        // 27. So 32 was almost certainly free - but that tool reads what Play
        // SERVES, and Play refuses an upload at a versionCode it has SEEN,
        // which includes anything pushed to a test track and never promoted.
        // The two are not the same question and only one of them is
        // measurable from outside.
        //
        // 34 / "1.32" is the first number since 33 that Play has not served.
        // Measured rather than assumed again, with tools/check-store-version.sh
        // on 2026-09-19: Play serves 1.31, versionCode 33 - the same number
        // this tree was carrying, so the tree could not be uploaded at all
        // until this line moved. That is the case the paragraph above does not
        // cover: 28 through 32 were numbers spent on builds that reached
        // nobody, and 33 is the first that reached users.
        //
        // What 34 carries, per tools/unreleased.sh: the start-time machine
        // banner, the "Show pre release" opt-in, the removal of the RomWBW
        // release filter, the two sound symbols romwbw_emu v1.47 made this
        // build need, and two emu_io_android corrections. It also carries a
        // user-visible fix no commit here made - the guest RTC overflow past
        // January 2038 on the two 32-bit ABIs, fixed in romwbw_emu's e41f686
        // and compiled in place out of the sibling checkout by
        // app/src/main/cpp/CMakeLists.txt.
        //
        // 35 / "1.33" is the first build R8 has ever run on, and it exists
        // because Play's Console reports DEX code optimization by category and
        // measured this app's Obfuscation at 2%, under the 25% it names as
        // affecting "visibility and publishing capabilities". 34 is the build
        // that was measured, so the fix needs a number of its own whatever
        // else is true.
        //
        // 36 / "1.34" is 35's code under a number Play has not seen, built
        // UNSIGNED on purpose - see CHANGELOG.md's 1.34 entry. Nothing in the
        // app changed. The number moved because 35 was built and handed over
        // and this tree cannot know whether Play has seen it, and a
        // versionCode it has merely SEEN is fatal to an upload.
        //
        // A versionCode costs nothing. A record that says the wrong thing does.
        versionCode = 36
        versionName = "1.34"

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
            // R8 runs, as of 1.33. It had been off since the initial commit,
            // and CLAUDE.md listed that as the first of four reasons a green
            // build here proves almost nothing.
            //
            // Play asked for it; this is not a cleanup. The Console reports DEX
            // code optimization by category, measured Obfuscation at 2%, and
            // says anything under 25% "may impact your visibility and
            // publishing capabilities". That is a publishing consequence rather
            // than a lint note, which is why this moved and why the
            // edge-to-edge warning reported beside it did not - that one is
            // about bytes in a library this app only takes a theme from, and
            // CHANGELOG.md's 1.24 entry is the investigation that closed it.
            //
            // What it costs: R8 renames everything it is not told to keep, and
            // JNI binds by name at the first call on a device.
            // app/proguard-rules.pro is the whole of what it is told, and
            // verifyJniNamesSurviveR8 below reads R8's own mapping file to
            // check that the telling worked - because on this project nothing
            // else can, and a build that gets this wrong is green.
            //
            // isShrinkResources is deliberately NOT turned on beside it. It is
            // a separate switch, it is not what Play measured, and it fails in
            // a different way - a resource dropped because only a layout names
            // it is an inflate-time crash, and the layouts here are what the
            // terminal draws into.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Assigned only when credentials actually resolved, because an
            // EMPTY signing config is not the same as no signing config and
            // the difference only shows on the path Play takes. An unresolved
            // config gives assembleRelease an unsigned APK - which is the trap
            // CLAUDE.md has always described - but gives bundleRelease a
            // NullPointerException out of FinalizeBundleTask, with no message
            // and no mention of signing. Measured on 2026-09-19 with AGP
            // 8.13.2 while building 1.34 deliberately unsigned.
            //
            // null here means "do not sign", which bundletool handles, so the
            // AAB comes out unsigned and the build succeeds. A checkout with
            // the properties file in place is unaffected.
            signingConfig =
                if (keystorePropertiesFile.exists()) signingConfigs.getByName("release") else null
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

// A JNI name survives R8, or the app installs and dies at the first call.
//
// JniNameParityTest is the guard CLAUDE.md describes and it cannot see this.
// It compares two SOURCE files, where every name is still spelled out, and
// passes whatever R8 then does to the DEX. Since 1.33 R8 runs on release
// builds, so there is a second place a JNI name can be lost and the parity test
// looks at neither end of it.
//
// R8 writes two files this reads, both under build/outputs/mapping/release:
//
//   mapping.txt  what was RENAMED. It is how Play retraces a crash, so every
//                rename is in it by construction. A name absent from it was
//                not renamed; a name present maps to itself or it moved.
//   usage.txt    what was REMOVED as unreachable.
//
// Neither alone answers the question. A native method missing from mapping.txt
// is either untouched or gone, and the two have opposite consequences: gone is
// harmless, because the matching export in libcpmdroid.so is then dead too and
// no caller exists on either side, while renamed is an UnsatisfiedLinkError on
// a device at the first call. So this accounts for every declaration against
// both files rather than inferring from one.
//
// Guard on the guard, the same one JniNameParityTest carries, and three of them
// here because a check that silently reads nothing is the failure this
// repository keeps finding:
//
//   - the `external fun` list must be non-empty, or the regex stopped matching;
//   - onOutput must be FOUND in mapping.txt, not merely unrenamed - it is the
//     one JNI name R8 records line numbers for, so it is present in an honest
//     mapping and absent from a misparsed one;
//   - R8 must have renamed SOME class somewhere. If it renamed nothing this
//     would pass a build with obfuscation off, which is the exact state Play
//     measured at 2% and asked to have fixed.
val verifyJniNamesSurviveR8 = tasks.register("verifyJniNamesSurviveR8") {
    group = "verification"
    description = "Fails a release build if R8 renamed anything JNI binds by name."

    // Named rather than discovered. If AGP renames this task, Gradle throws
    // UnknownTaskException here, which is the loud end of the two failures
    // available - the quiet one being a check that runs before R8 and reads the
    // previous build's mapping.
    mustRunAfter("minifyReleaseWithR8")

    val engineSource =
        layout.projectDirectory.file("src/main/java/com/awohl/cpmdroid/EmulatorEngine.kt").asFile
    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    val usageFile = layout.buildDirectory.file("outputs/mapping/release/usage.txt")
    inputs.file(engineSource)
    outputs.upToDateWhen { false }

    doLast {
        val mapping = mappingFile.get().asFile
        val usage = usageFile.get().asFile
        for (f in listOf(mapping, usage)) {
            if (!f.isFile) {
                throw GradleException(
                    "R8 wrote no $f. This runs after minifyReleaseWithR8 and cannot " +
                        "check a build that did not minify - if isMinifyEnabled was " +
                        "turned off, remove this task with it and put the gap back in " +
                        "CLAUDE.md rather than leaving a check that cannot run."
                )
            }
        }

        val declared = Regex("""external\s+fun\s+(\w+)""")
            .findAll(engineSource.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
        if (declared.isEmpty()) {
            throw GradleException(
                "no `external fun` found in ${engineSource.name} - the regex stopped " +
                    "matching, and this check was about to pass by comparing nothing."
            )
        }

        val mappingLines = mapping.readLines()

        // Did R8 obfuscate at all? A class line reads "<from> -> <to>:".
        val renamedClasses = mappingLines.count { line ->
            line.isNotEmpty() && !line.first().isWhitespace() && !line.startsWith("#") &&
                line.endsWith(":") && line.contains(" -> ") &&
                line.substringBefore(" -> ") != line.substringAfter(" -> ").dropLast(1)
        }
        if (renamedClasses == 0) {
            throw GradleException(
                "R8 renamed no class in $mapping. Obfuscation did not happen, which is " +
                    "the state Play measured at 2% - a JNI check passing here would mean " +
                    "nothing."
            )
        }

        val classPrefix = "com.awohl.cpmdroid.EmulatorEngine -> "
        val at = mappingLines.indexOfFirst { it.startsWith(classPrefix) }
        if (at < 0) throw GradleException("EmulatorEngine does not appear in $mapping at all.")
        val classNow = mappingLines[at].substringAfter(" -> ").trimEnd(':')
        if (classNow != "com.awohl.cpmdroid.EmulatorEngine") {
            throw GradleException(
                "R8 renamed EmulatorEngine to $classNow. Every " +
                    "Java_com_awohl_cpmdroid_EmulatorEngine_* export in " +
                    "emu_io_android.cpp is unreachable in this build."
            )
        }

        // A block is the indented lines that follow a header. A mapping member
        // reads "<lines>:<type> <name>(<args>) -> <newName>". R8's own comment
        // lines are taken with them - the first, "sourceFile", sits flush left
        // directly under the class line, so stopping at the first unindented
        // line reads every class block as empty - and they carry no " -> ", so
        // they drop out below.
        fun blockAfter(lines: List<String>, header: Int) =
            lines.drop(header + 1).takeWhile {
                it.isNotEmpty() && (it.first().isWhitespace() || it.startsWith("#"))
            }

        val renames = blockAfter(mappingLines, at).mapNotNull { line ->
            val arrow = line.lastIndexOf(" -> ")
            if (arrow < 0) return@mapNotNull null
            val from = line.substring(0, arrow).substringBefore('(').trim().substringAfterLast(' ')
            from to line.substring(arrow + 4).trim()
        }.toMap()

        // What R8 deleted. usage.txt lists a wholly removed class on its own
        // line, and a partly emptied one as "<class>:" plus indented members.
        val usageLines = usage.readLines()
        if (usageLines.any { it == "com.awohl.cpmdroid.EmulatorEngine" }) {
            throw GradleException(
                "R8 removed EmulatorEngine entirely, per $usage. Nothing can bind to it."
            )
        }
        val usageAt = usageLines.indexOfFirst { it == "com.awohl.cpmdroid.EmulatorEngine:" }
        val dropped = if (usageAt < 0) emptySet() else blockAfter(usageLines, usageAt)
            .map { it.substringBefore('(').trim().substringAfterLast(' ') }
            .toSet()

        if ("onOutput" in dropped) {
            throw GradleException(
                "R8 removed onOutput, per $usage. Nothing in Kotlin calls it - " +
                    "emu_io_android.cpp finds it with GetMethodID - so the keep rule in " +
                    "app/proguard-rules.pro is gone or stopped matching, and every byte " +
                    "the guest writes would reach a null method ID."
            )
        }
        val callbackNow = renames["onOutput"]
            ?: throw GradleException(
                "onOutput is not under EmulatorEngine in $mapping. It is the one JNI " +
                    "name R8 records line numbers for, so this is a misparse of the " +
                    "mapping format rather than a finding about the build."
            )
        if (callbackNow != "onOutput") {
            throw GradleException(
                "R8 renamed onOutput to $callbackNow, and GetMethodID asks for the name."
            )
        }

        val kept = declared - dropped
        val moved = kept.filter { renames[it] != null && renames[it] != it }
        if (moved.isNotEmpty()) {
            throw GradleException(
                "R8 renamed what JNI binds by name: " +
                    moved.joinToString(", ") { "$it -> " + renames[it] } +
                    ". This build installs and throws UnsatisfiedLinkError at the first " +
                    "call. Fix app/proguard-rules.pro."
            )
        }
        if (kept.isEmpty()) {
            throw GradleException(
                "R8 removed all ${declared.size} native declarations. Nothing in this " +
                    "build reaches the emulator."
            )
        }

        // A dropped declaration is reported, not failed: its export in the .so
        // is dead too. It is worth printing because JniNameParityTest goes on
        // matching the pair in source long after R8 stopped shipping one.
        val droppedNatives = declared.intersect(dropped)
        val tail = if (droppedNatives.isEmpty()) "." else
            "; " + droppedNatives.size + " dropped as unreachable (" +
                droppedNatives.joinToString(", ") + ")."
        logger.lifecycle(
            "verifyJniNamesSurviveR8: EmulatorEngine and ${kept.size} of " +
                "${declared.size} native declarations keep their names; onOutput too" + tail
        )
    }
}

// dependsOn rather than finalizedBy: a finalizer runs after the thing it
// finalizes and cannot stop what follows, so bundleRelease would write the AAB
// and only then report that it cannot be installed.
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }
    .configureEach { dependsOn(verifyJniNamesSurviveR8) }

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
