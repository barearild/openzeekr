import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Load baked secrets from a gitignored file so local builds include them but the
// repo never does. Missing file (fresh clone / CI) -> all values empty -> the
// app simply starts blank and is configured on the Settings screen.
val secretsProps = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun bakedSecret(key: String): String =
    (secretsProps.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.openzeekr.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openzeekr.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Baked-in defaults from secrets.properties (gitignored). Empty when the
        // file is absent. ConfigStore seeds these on first run; nothing is
        // hardcoded in source.
        //
        // ONLY the six app-global secrets are baked. The throwaway ACCOUNT
        // (email / password / vin / userId) is deliberately NOT compiled in —
        // it is entered once on the Settings screen and lives only in encrypted
        // on-device storage, so no personal credential ever ends up in an APK.
        buildConfigField("String", "SEC_HMAC_ACCESS_KEY", "\"${bakedSecret("HMAC_ACCESS_KEY")}\"")
        buildConfigField("String", "SEC_HMAC_SECRET_KEY", "\"${bakedSecret("HMAC_SECRET_KEY")}\"")
        buildConfigField("String", "SEC_PASSWORD_PUBLIC_KEY", "\"${bakedSecret("PASSWORD_PUBLIC_KEY")}\"")
        buildConfigField("String", "SEC_PROD_SECRET", "\"${bakedSecret("PROD_SECRET")}\"")
        buildConfigField("String", "SEC_VIN_KEY", "\"${bakedSecret("VIN_KEY")}\"")
        buildConfigField("String", "SEC_VIN_IV", "\"${bakedSecret("VIN_IV")}\"")
        // HF/xchanger (ECARX) signing key = NativeSecretLib.getTSPSecretValue("EU","ONLINE").
        // App-global secret (same for every EU Zeekr install); keep it in secrets.properties,
        // never commit the value.
        buildConfigField("String", "SEC_XCHANGER_SIGN_SECRET", "\"${bakedSecret("XCHANGER_SIGN_SECRET")}\"")
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle bcprov + bcpkix both ship these OSGi/version manifests.
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/versions/**/OSGI-INF/**"
        resources.excludes += "/META-INF/*.SF"
        resources.excludes += "/META-INF/*.DSA"
        resources.excludes += "/META-INF/*.RSA"
    }
}

dependencies {
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

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Encrypted config storage (secrets are never in code)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DK BLE crypto: BouncyCastle provides full-point EC (ECDH X‖Y), which plain
    // JCE KeyAgreement can't expose. All other primitives use platform JCE.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // DK provisioning: PKCS#10 CSR builder for cert enrolment.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
