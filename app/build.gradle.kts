import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing (WP5): reads app-auto/keystore.properties (gitignored) when present.
// On a clean checkout without it, assembleRelease falls back to the debug signing key
// so the build stays green — see docs/SIGNING.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.powerpoppalace.subwaveauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.powerpoppalace.subwaveauto"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.10.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
    }

    // WP3: Robolectric unit tests (BrowseTree constructs media3 MediaItems).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Media3 — one version for all media3 artifacts (plan §1 pin: 1.8.0)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Compose (placeholder UI now, WP4 later)
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (WP1)
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Local unit tests run against the mockable android.jar whose org.json stubs
    // throw — the real artifact makes StationApi's parse tests run on the JVM.
    testImplementation("org.json:json:20240303")
    // WP3: media3 MediaItem.Builder needs a real android.net.Uri (the mockable
    // android.jar's Uri.parse throws) — Robolectric supplies real framework classes.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
