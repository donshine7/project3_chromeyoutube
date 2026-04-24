plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

val buildPythonPath = providers.gradleProperty("chaquopy.buildPythonPath")
    .orElse("python")
val enableOnDeviceNative = providers.gradleProperty("enableOnDeviceNative")
    .orElse("false")
    .map { it.equals("true", ignoreCase = true) }
    .get()

android {
    namespace = "com.example.project3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.project3"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        if (enableOnDeviceNative) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17")
                }
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    if (enableOnDeviceNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython(buildPythonPath.get())
        pip {
            install("yt-dlp==2026.3.17")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.mrljdx:ffmpeg-kit-full:6.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
