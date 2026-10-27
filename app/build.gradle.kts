plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.runasakura.phoneagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.runasakura.phoneagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.14.2-mvp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // On-device LLM (MNN) is arm64 only; 16 KB page aligned for Android 15+/16.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared"); cppFlags += listOf("-std=c++17") } }
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    // Honor MagicOS / some OEM installers reject APKs that only have v2 signing (no META-INF/*.RSA) with
    // 「该安装包未包含任何证书」. Force v1+v2+v3 and a repo-local debug keystore so every assembleDebug is signed.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            // Same debug key for prerelease sideloads (no Play upload). Same cert as historical debug builds.
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            // Same package as debug sideloads so upgrades replace the bad 0.14.1; not debuggable (OEM "pure mode" friendlier).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Offline simulator runs the real AgentLoop on the JVM: framework stubs (Log, Build…) return defaults.
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // sherpa-onnx (offline ASR): only the JNI lib + onnxruntime are loaded; the C/C++ API libs are unused.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.json:json:20240303")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Bundled (offline, no GMS) Chinese OCR for apps that hide their accessibility tree (e.g. WeChat)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Bundled offline streaming ASR (k2-fsa sherpa-onnx 2.0.1, Apache-2.0); model downloaded on demand from ModelScope.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // JVM UI rendering (no device): screenshot tests of the chat / settings / overlay views.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
}
