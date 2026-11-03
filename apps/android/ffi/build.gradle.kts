import java.util.zip.ZipFile

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

// ---------------------------------------------------------------------------------------------
// Android native packaging (x86_64, API 26). Opt-in: -PupspaAndroidNative=true
// Requires cargo-ndk and NDK r27c via ANDROID_NDK_HOME or <sdk>/ndk/<version>.
// ---------------------------------------------------------------------------------------------
val upspaNdkVersion = "27.2.12479018"
val androidAbi = "x86_64"
val jniLibsDir: File = projectDir.resolve("src/main/jniLibs")
val ndkHome: String = System.getenv("ANDROID_NDK_HOME")
    ?: android.sdkDirectory.resolve("ndk/$upspaNdkVersion").absolutePath

val cargoBuildAndroid = tasks.register<Exec>("cargoBuildAndroid") {
    description = "Builds libupspa_mobile_ffi.so for Android $androidAbi (API 26) into src/main/jniLibs."
    group = "build"
    workingDir = repoRoot
    environment("ANDROID_NDK_HOME", ndkHome)
    commandLine("cargo", "ndk", "-t", androidAbi, "-P", "26", "-o", jniLibsDir.absolutePath,
        "build", "--locked", "--release", "-p", "upspa-mobile-ffi")
    inputs.dir(repoRoot.resolve("crates/upspa-mobile-ffi/src"))
    inputs.dir(repoRoot.resolve("crates/upspa-core/src"))
    inputs.file(repoRoot.resolve("Cargo.lock"))
    outputs.file(jniLibsDir.resolve("$androidAbi/libupspa_mobile_ffi.so"))
    doFirst {
        val props = File(ndkHome, "source.properties")
        if (!props.isFile) throw GradleException("NDK not found at $ndkHome (set ANDROID_NDK_HOME)")
        val rev = props.readLines().firstOrNull { it.startsWith("Pkg.Revision") }?.substringAfter("=")?.trim()
        if (rev != upspaNdkVersion) throw GradleException("NDK $rev found, expected $upspaNdkVersion")
    }
}

if (providers.gradleProperty("upspaAndroidNative").orNull == "true") {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(cargoBuildAndroid) }
}

tasks.register("verifyAndroidNative") {
    description = "Fails unless the AAR/APK contains $androidAbi/libupspa_mobile_ffi.so. -PupspaArtifact=<path>"
    group = "verification"
    val artifactPath = providers.gradleProperty("upspaArtifact")
        .orElse(layout.buildDirectory.file("outputs/aar/ffi-debug.aar").map { it.asFile.absolutePath })
    doLast {
        val given = File(artifactPath.get())
        val artifact = if (given.isAbsolute) given else rootDir.resolve(given.path)
        if (!artifact.isFile) throw GradleException("artifact not found: $artifact")
        val libs = ZipFile(artifact).use { z: ZipFile ->
            z.entries().asSequence().map { it.name }.filter { it.endsWith(".so") }.toList()
        }
        logger.lifecycle("native entries in ${artifact.name}: $libs")
        val expected = setOf("jni/$androidAbi/libupspa_mobile_ffi.so", "lib/$androidAbi/libupspa_mobile_ffi.so")
        if (libs.none { it in expected }) throw GradleException("MISSING $androidAbi/libupspa_mobile_ffi.so in ${artifact.name}")
        logger.lifecycle("OK: $androidAbi/libupspa_mobile_ffi.so present in ${artifact.name}")
    }
}

// ---------------------------------------------------------------------------------------------
// Emulator smoke test: runs the existing MobileContractTest on a device through the packaged
// jniLibs .so and generated bindings. Run with -PupspaAndroidNative=true and a class filter.
// ---------------------------------------------------------------------------------------------
android {
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets { getByName("androidTest").java.srcDirs("src/test/java") }
}
dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.org.json)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
