plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ikegami.camera2probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ikegami.camera2probe"
        minSdk = 28
        targetSdk = 35
        versionCode = 15
        versionName = "0.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Keep the already deployed fixed certificate so v0.6.x can update in place,
            // while producing a non-debuggable release build.
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
