import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.upspa.research.provider"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.upspa.research.provider"
        // API 26 is the Autofill Framework floor; part of the minimum-version research question.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-research"
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

    // Reproducible three-API device matrix (see root README for the rationale and the
    // manual-AVD fallback if an image is unavailable for Gradle Managed Devices).
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
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.autofill)
}
