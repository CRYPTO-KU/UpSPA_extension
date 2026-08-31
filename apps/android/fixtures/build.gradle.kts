plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.upspa.mobile.fixtures"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.upspa.mobile.fixtures"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-bootstrap"
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
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
