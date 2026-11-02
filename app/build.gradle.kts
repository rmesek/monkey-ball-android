plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.rmesek.monkeyball"
    compileSdk {
        version = release(37)
    }
    // The NDK cargo-ndk links the Rust library with (SDK Manager -> SDK Tools -> NDK (Side by side)).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.rmesek.monkeyball"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumented tests (app/src/androidTest): each in a process of its own, because the engine runs once
    // per process (native/src/lib.rs).
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The game itself: native/ (Rust) built into src/main/jniLibs/<abi>/libmonkeyball.so before every build.
// arm64-v8a for phones, x86_64 for the emulator. Cargo decides what needs rebuilding. API level 26 = minSdk.
val ndkDir = androidComponents.sdkComponents.ndkDirectory
val cargoNdk = tasks.register<Exec>("cargoNdk") {
    workingDir = rootProject.file("native")
    commandLine(
        "cargo", "ndk", "--platform", "26", "-t", "arm64-v8a", "-t", "x86_64",
        "-o", file("src/main/jniLibs").absolutePath, "build", "--release",
    )
    // The NDK of ndkVersion above, so cargo-ndk needs no environment of its own. Read when the task is
    // configured (after the android block), as a plain string the configuration cache can keep.
    environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    // GameActivity: the activity the Rust side (winit's android-game-activity backend) runs in.
    implementation(libs.games.activity)
    // Its base class and the Theme.AppCompat the activity uses; games-activity brings it anyway.
    implementation(libs.androidx.appcompat)
    // Instrumented tests only (not in the app): JUnit 4 on Android, ActivityScenario, the runner, and the
    // orchestrator that gives each test its own process.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestUtil(libs.androidx.test.orchestrator)
}
