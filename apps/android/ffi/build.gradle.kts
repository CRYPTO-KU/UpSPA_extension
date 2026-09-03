plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.upspa.mobile.ffi"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        // Generated bindings land here; see scripts/generate_mobile_bindings.sh.
        getByName("main").java.srcDirs("src/main/java", "src/main/generated")
        // Built .so artifacts land here; see docs/mobile-ffi-contract.md.
        getByName("main").jniLibs.srcDirs("src/main/jniLibs")
    }
}

dependencies {
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
    testImplementation(libs.jna)
}
