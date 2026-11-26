plugins {
    id("com.android.application")
}

// Release signing comes from the environment when a keystore is provided;
// otherwise the release APK is signed with the local debug key so it installs.
val releaseKeystore: String? = System.getenv("DSH_ANDROID_KEYSTORE")

android {
    namespace = "ai.deepseek.harness"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.deepseek.harness.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.3-alpha.2"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("DSH_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DSH_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("DSH_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The VFS image and lazy archives are already gzip members, stored as `.gz.raw`
        // because the asset pipeline would strip and inflate a `.gz` suffix.
        noCompress += listOf(".raw")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
