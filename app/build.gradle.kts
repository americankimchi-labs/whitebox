import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val targetAndroidSdk = 35

/*
 * Release signing reads a local keystore through local.properties (gitignored); the keystore and passwords are never
 * committed. One-time setup:
 *
 *   keytool -genkeypair -keystore ~/.whitebox/whitebox-release.jks -storetype PKCS12 -alias whitebox \
 *       -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=White Box, O=americankimchi labs, C=KR"
 *
 * then in local.properties:
 *   whitebox.release.storeFile=/Users/<you>/.whitebox/whitebox-release.jks
 *   whitebox.release.storePassword=…
 *   whitebox.release.keyAlias=whitebox
 *   whitebox.release.keyPassword=…
 *
 * Without these keys (e.g. a fresh clone) the release build is produced unsigned and debug builds are unaffected.
 */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseStore: String? = localProps.getProperty("whitebox.release.storeFile")

android {
    namespace = "com.americankimchilabs.whitebox"
    compileSdk = targetAndroidSdk

    defaultConfig {
        applicationId = "com.americankimchilabs.whitebox"
        minSdk = 28
        targetSdk = targetAndroidSdk
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicleSdk.get()}\"")
        // Pleos My Project credentials for server speech (local.properties, never committed). Empty until Pleos opens
        // app registration; the speech SDKs then run on-device only.
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${localProps.getProperty("pleos.clientId", "")}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${localProps.getProperty("pleos.clientSecret", "")}\"")
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = localProps.getProperty("whitebox.release.storePassword")
                keyAlias = localProps.getProperty("whitebox.release.keyAlias")
                keyPassword = localProps.getProperty("whitebox.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not minified: stack traces stay readable and the APK can be inspected.
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":vehicle-core"))
    implementation(project(":data"))
    implementation(project(":vehicle-fake"))
    implementation(project(":vehicle-pleos"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
