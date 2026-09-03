import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.upspa.research.fixtures"
    compileSdk = 35

    defaultConfig {
        // Separate applicationId so autofill requests are genuinely cross-package.
        applicationId = "com.upspa.research.fixtures"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-research"

        // Instrumented experiment harness (EXP-001..003), see src/androidTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Same reproducible three-API matrix as the provider module (see root README).
    testOptions {
        managedDevices {
            devices {
                create<ManagedVirtualDevice>("api26") {
                    device = "Pixel 2"
                    apiLevel = 26
                    systemImageSource = "google"
                }
                create<ManagedVirtualDevice>("api30") {
                    device = "Pixel 4"
                    apiLevel = 30
                    systemImageSource = "google"
                }
                create<ManagedVirtualDevice>("api34") {
                    device = "Pixel 8"
                    apiLevel = 34
                    systemImageSource = "google"
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.autofill)

    // Jetpack Compose fixture (research topic 10: real-app compatibility).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // Automated autofill experiments: UIAutomator drives the system-owned fill UI,
    // Espresso drives in-app views.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
