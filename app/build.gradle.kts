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

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}