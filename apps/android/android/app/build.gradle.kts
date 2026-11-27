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
        // SDK 28 is the last target that lets an app execute programs from its own storage
        // (the full engine's Node.js, bash, and git) and grants classic shared-storage access, as Termux does.
        targetSdk = 28
        versionCode = 1
        versionName = "0.2.3-alpha.2"
    }

    lint {
        // The old target SDK is deliberate; see defaultConfig.
        disable += "ExpiredTargetSdkVersion"
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
        noCompress += listOf(".raw", ".deb", ".tgz")
    }
}

dependencies {
    // Termux packages are .deb files whose data member is xz-compressed.
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
}
