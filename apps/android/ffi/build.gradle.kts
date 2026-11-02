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
    testImplementation(libs.org.json)
}

// ---------------------------------------------------------------------------------------------
// Host (JVM) native library for unit tests.
//
// JVM unit tests run on the build machine, not on a device, so they need the *host* build of
// libupspa_mobile_ffi (.so / .dylib / .dll), not the Android jniLibs. This builds it with the
// workspace's pinned toolchain inputs (--locked) and points JNA at it, so
// `./gradlew :ffi:testDebugUnitTest` works on a clean checkout with only `cargo` on PATH.
// ---------------------------------------------------------------------------------------------
val repoRoot: File = rootDir.parentFile.parentFile
val cargoTargetDir: File = System.getenv("CARGO_TARGET_DIR")?.let(::File) ?: repoRoot.resolve("target")
val hostLibDir: File = cargoTargetDir.resolve("release")

// Shared, read-only conformance corpus. Override with -PupspaVectorCorpus=/path/to/copy.json
// to point the conformance test at a (for example, deliberately corrupted) temporary copy.
val vectorCorpus: File = providers.gradleProperty("upspaVectorCorpus")
    .map { File(it) }
    .getOrElse(repoRoot.resolve("test-vectors/compatibility-profile-v1/vectors.json"))

val cargoBuildHost = tasks.register<Exec>("cargoBuildHost") {
    description = "Builds the host cdylib of upspa-mobile-ffi for JVM unit tests."
    workingDir = repoRoot
    commandLine("cargo", "build", "--locked", "--release", "-p", "upspa-mobile-ffi")
    inputs.dir(repoRoot.resolve("crates/upspa-mobile-ffi/src"))
    inputs.dir(repoRoot.resolve("crates/upspa-core/src"))
    inputs.file(repoRoot.resolve("Cargo.lock"))
    outputs.dir(hostLibDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    inputs.file(vectorCorpus)
    systemProperty("jna.library.path", hostLibDir.absolutePath)
    systemProperty("upspa.vectorCorpus", vectorCorpus.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        // FULL so assertion messages are visible (SHORT prints only the exception type and line).
        // Assertion messages in these tests carry vector IDs only, never secret values.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
