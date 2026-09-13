plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openzeekr.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openzeekr.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // App-global secrets are baked in :core (SecretsConfig + BuildConfig live there).
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle bcprov + bcpkix (pulled in transitively via :core) ship these.
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/versions/**/OSGI-INF/**"
        resources.excludes += "/META-INF/*.SF"
        resources.excludes += "/META-INF/*.DSA"
        resources.excludes += "/META-INF/*.RSA"
    }
}

dependencies {
    // Shared BLE/DK/crypto/cloud logic (also carries okhttp/retrofit/serialization/
    // security-crypto/bouncycastle transitively via `api`).
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Map (free, no API key): MapLibre GL + OpenFreeMap tiles. Used for the parked-car
    // location; turn-by-turn navigation is handed off to the phone's nav app via deeplink.
    implementation("org.maplibre.gl:android-sdk:11.13.5")
}
