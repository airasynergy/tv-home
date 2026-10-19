plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aira.tvhome"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aira.tvhome"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // One permanent key, so every new build installs over the old one (settings kept).
    signingConfigs {
        create("tvhome") {
            storeFile = file("tvhome.keystore")
            storePassword = "tvhome-key"
            keyAlias = "tvhome"
            keyPassword = "tvhome-key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tvhome")
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

// No extra libraries: plain Android framework only, so it stays small and fast.
