plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    testBuildType = if (System.getenv("FUGUANG_TEST_RELEASE") == "1") "release" else "debug"
    namespace = "com.example.parallaxwallpaper"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.parallaxwallpaper"
        minSdk = 27          // NNAPI requires API 27+
        targetSdk = 35
        versionCode = 6
        versionName = "6"
        buildConfigField("String", "ACCOUNT_BASE_URL", "\"https://fuguang.startimetable.xyz\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("fuguang") {
            val path = System.getenv("FUGUANG_KEYSTORE")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("FUGUANG_SIGNING_PASSWORD")
                keyAlias = "fuguang"
                keyPassword = System.getenv("FUGUANG_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (!System.getenv("FUGUANG_KEYSTORE").isNullOrBlank()) signingConfig = signingConfigs.getByName("fuguang")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }

    androidResources {
        // Prevent Gradle from compressing the ONNX model in the APK
        noCompress += listOf("onnx", "tflite")
    }

    packaging {
        jniLibs {
            // ORT ships prebuilt .so libs; keep them all
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ort.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.ui.tooling)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
