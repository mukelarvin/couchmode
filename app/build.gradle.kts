plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from environment variables, never from values
// committed to the repo. Locally these are unset, so a local `assembleRelease`
// just produces an unsigned APK — that's fine for local testing. CI (see
// .github/workflows/release.yml) sets these from GitHub secrets. See
// RELEASING.md for how to generate the keystore and register the secrets.
val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")

android {
    namespace = "com.couchmode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.couchmode.app"
        minSdk = 26
        targetSdk = 36
        // Bump versionCode on every release build — Obtainium/Android both
        // key updates off this, and it must strictly increase. versionName
        // is just the human-readable string (see spec.md, Naming & distribution).
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        compose = true
        // Needed for BuildConfig.APPLICATION_ID/VERSION_CODE/DEBUG, used by
        // shizuku/InputReader.kt to build UserServiceArgs (Phase 2). AGP 9
        // no longer generates BuildConfig by default.
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Phase 2 — Shizuku wiring. Latest published version as of this commit;
    // check https://github.com/RikkaApps/Shizuku-API for anything newer.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
