plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aas.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aas.app"
        minSdk = 29
        // BYDMate targets 29 on DiLink. Keeping the same target avoids newer
        // Android behavior changes that are irrelevant to a sideloaded head-unit app.
        targetSdk = 29
        versionCode = 151
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}

// Models are bundled into assets before the APK build. On Windows the Python
// launcher is usually `py`; on Linux/macOS it is normally `python3`.
val prepareVoskModels by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine(
            "cmd", "/c",
            "where py >nul 2>nul && py scripts/prepare_vosk_models.py || python scripts/prepare_vosk_models.py"
        )
    } else {
        commandLine("python3", "scripts/prepare_vosk_models.py")
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareVoskModels)
}
