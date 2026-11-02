package org.upspa.catalog

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the independent review of PR #33 (scripts/mutation-check.sh):
 *
 * C1: the cleanup trap handled EXIT, INT and TERM alike and exited with `$?` of the last command,
 *     so SIGTERM or SIGINT during the run exited 0.
 * C2: a failing `rm -rf` of the isolation directory inside the trap (under `set -e`) replaced the exit code.
 * C3: with `CDPATH` set, `cd` printed the directory into ABS_SCRIPT/REAL_ROOT and the script failed.
 * C4: the tests-executed guard only rejected 0; a non-integer `tests=` value passed it.
 * C5: the baseline `./gradlew test` did not force a fresh run (up-to-date state or build cache could satisfy it).
 *
 * Every test runs the real script in a hermetic temp-dir fixture: a git repository holding a copy of the
 * script, a stub gradlew that records its arguments, and a synthetic verifier source. No real Gradle starts.
 */
class IndependentReviewScriptRegressionTest {

    private class ProcessResult(val exitCode: Int, val output: String)

    private val createdDirs = mutableListOf<File>()

    companion object {
        private const val PROCESS_TIMEOUT_SECONDS = 120L
        private const val FIXTURE_VERIFIER_PATH =
            "src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt"
        private const val SUITE_XML_PATH =
            "build/test-results/test/TEST-org.upspa.assetlinks.adversarial.IdentityAdversarialSuite.xml"

        // Synthetic verifier source: contains the M1 grep pattern exactly once. It is never compiled.
        private val FIXTURE_VERIFIER_SOURCE = """
            package org.upspa.assetlinks.verifier

            // Synthetic fixture for the mutation-check script tests. Not production code.
            object FixturePackageCheck {
                fun matches(target: Target, appSigningInfo: Info): Boolean =
                    target.packageName == appSigningInfo.packageName
            }
        """.trimIndent() + "\n"

        /**
         * Stub Gradle wrapper. It appends its arguments to STUB_STATE_DIR/gradlew-args.log and its working
         * directory (the script's isolation directory) to STUB_STATE_DIR/isolation-dir.
         * - baseline `test` (no --tests): touches baseline-started, sleeps STUB_BASELINE_SLEEP seconds, passes;
         * - `compileKotlin compileTestKotlin`: fails when the injected compile-fault marker is present;
         * - `test ... --tests ...`: writes the suite XML selected by STUB_SUITE_XML (tests= value) with one
         *   failing PACKAGE_MISMATCH_CASE_DIFFERENCE case, or writes nothing when STUB_SUITE_XML is unset.
         */
        private val STUB_GRADLEW = """
            #!/usr/bin/env bash
            state="${'$'}{STUB_STATE_DIR:?STUB_STATE_DIR must be set}"
            echo "${'$'}*" >> "${'$'}state/gradlew-args.log"
            pwd > "${'$'}state/isolation-dir"
            case "${'$'}1" in
                test)
                    case " ${'$'}* " in
                        *" --tests "*)
                            if [ -n "${'$'}{STUB_SUITE_XML:-}" ]; then
                                mkdir -p build/test-results/test
                                {
                                    echo '<?xml version="1.0" encoding="UTF-8"?>'
                                    echo "<testsuite name=\"org.upspa.assetlinks.adversarial.IdentityAdversarialSuite\" tests=\"${'$'}STUB_SUITE_XML\" skipped=\"0\" failures=\"1\" errors=\"0\">"
                                    echo '  <testcase name="PACKAGE_MISMATCH_CASE_DIFFERENCE" classname="org.upspa.assetlinks.adversarial.IdentityAdversarialSuite" time="0.01">'
                                    echo '    <failure message="stub failure">stub failure</failure>'
                                    echo '  </testcase>'
                                    echo '</testsuite>'
                                } > "$SUITE_XML_PATH"
                            fi
                            exit 0
                            ;;
                        *)
                            touch "${'$'}state/baseline-started"
                            sleep "${'$'}{STUB_BASELINE_SLEEP:-0}"
                            echo "3 tests completed"
                            exit 0
                            ;;
                    esac
                    ;;
                compileKotlin)
                    if grep -rq "SYNTAX_ERROR_INJECTED_FOR_COMPILE_FAULT" src; then
                        echo "e: stub compile failure: SYNTAX_ERROR_INJECTED_FOR_COMPILE_FAULT found under src/"
                        exit 1
                    fi
                    exit 0
                    ;;
                *)
                    echo "gradlew stub: unsupported arguments: ${'$'}*"
                    exit 99
                    ;;
            esac
        """.trimIndent() + "\n"

        /** Stub rm placed first on PATH: refuses to remove exactly the recorded isolation directory. */
        private val STUB_RM = """
            #!/usr/bin/env bash
            iso="${'$'}(cat "${'$'}STUB_STATE_DIR/isolation-dir" 2>/dev/null)"
            for arg in "${'$'}@"; do
                if [ -n "${'$'}iso" ] && [ "${'$'}arg" = "${'$'}iso" ]; then
                    echo "rm stub: refusing to remove isolation directory ${'$'}arg" >&2
                    exit 1
                fi
            done
            exec "${'$'}REAL_RM" "${'$'}@"
        """.trimIndent() + "\n"

        private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        /**
         * Resolves bash from PATH. On Windows, %SystemRoot%\System32\bash.exe and WindowsApps\bash.exe
         * are WSL launchers, not a bash for this host, so they are ignored.
         */
        private val bashExecutable: File? by lazy {
            val systemRoot = System.getenv("SystemRoot")?.let { File(it).canonicalPath.lowercase() }
            val name = if (isWindows) "bash.exe" else "bash"
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { File(it, name) }
                .filter { it.isFile }
                .filterNot { candidate ->
                    val path = candidate.canonicalPath.lowercase()
                    isWindows && ((systemRoot != null && path.startsWith(systemRoot)) || path.contains("windowsapps"))
                }
                .firstOrNull { candidate ->
                    try {
                        val process = ProcessBuilder(candidate.path, "--version").redirectErrorStream(true).start()
                        val finished = process.waitFor(10, TimeUnit.SECONDS)
                        if (!finished) process.destroyForcibly()
                        finished && process.exitValue() == 0
                    } catch (_: Exception) {
                        false
                    }
                }
        }

        /** Forward-slash form of a path, which both POSIX bash and Git for Windows bash accept. */
        private fun shellPath(file: File): String = file.absolutePath.replace('\\', '/')
    }

    private class Fixture(val root: File, val state: File, val bin: File)

    @AfterEach
    fun removeFixtures() {
        createdDirs.reversed().forEach { it.deleteRecursively() }
    }

    private fun requireBash(): File {
        val bash = bashExecutable
        assumeTrue(
            bash != null,
            "No usable bash on PATH (WSL launchers under %SystemRoot% are ignored). " +
                "On Windows, prepend Git for Windows' bin directory to PATH to run this test."
        )
        return bash!!
    }

    private fun realScriptText(): String {
        val scriptFile = File("scripts/mutation-check.sh").canonicalFile
        assertTrue(scriptFile.exists(), "scripts/mutation-check.sh must exist")
        return scriptFile.readText().replace("\r\n", "\n")
    }

    private fun makeExecutable(file: File) {
        if (file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    private fun buildFixture(bash: File): Fixture {
        val root = Files.createTempDirectory("mutation-review-fixture").toFile()
        val state = Files.createTempDirectory("mutation-review-state").toFile()
        createdDirs += root
        createdDirs += state
        val bin = File(state, "bin").apply { mkdirs() }

        File(root, "scripts").mkdirs()
        File(root, "scripts/mutation-check.sh").writeText(realScriptText())
        File(root, "build.gradle.kts").writeText("// Synthetic fixture: no real Gradle build.\n")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"mutation-review-fixture\"\n")
        File(root, "gradlew").writeText(STUB_GRADLEW)
        File(root, "gradlew.bat").writeText("@rem Synthetic fixture stub; never executed.\r\nexit /b 1\r\n")
        File(root, "gradle").mkdirs()
        File(root, "docs").mkdirs()
        File(root, "docs/README.md").writeText("Synthetic mutation-check fixture.\n")
        File(root, FIXTURE_VERIFIER_PATH).apply { parentFile.mkdirs() }.writeText(FIXTURE_VERIFIER_SOURCE)
        makeExecutable(File(root, "gradlew"))

        val gitInit = runProcess(root, emptyMap(), bash.path, "-c", "git init -q")
        assertEquals(0, gitInit.exitCode, "git init failed in fixture:\n${gitInit.output}")
        return Fixture(root, state, bin)
    }

    private fun runProcess(directory: File, env: Map<String, String>, vararg command: String): ProcessResult {
        val builder = ProcessBuilder(*command).directory(directory).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }
        reader.start()
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(5_000)
            throw AssertionError("Command timed out after ${PROCESS_TIMEOUT_SECONDS}s: ${command.joinToString(" ")}\n$output")
        }
        reader.join()
        return ProcessResult(process.exitValue(), output.toString())
    }

    private fun stubEnv(fixture: Fixture, extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf("STUB_STATE_DIR" to shellPath(fixture.state)) + extra

    private fun runScript(bash: File, fixture: Fixture, env: Map<String, String>, vararg args: String): ProcessResult =
        runProcess(fixture.root, env, bash.path, "scripts/mutation-check.sh", *args)

    /**
     * Runs [script] with bash from a file in the fixture state directory. A file is used instead of
     * `bash -c`, because Windows command-line quoting does not preserve embedded double quotes.
     */
    private fun runShell(bash: File, fixture: Fixture, env: Map<String, String>, script: String): ProcessResult {
        val file = Files.createTempFile(fixture.state.toPath(), "wrapper", ".sh").toFile()
        file.writeText(script + "\n")
        return runProcess(fixture.root, env, bash.path, shellPath(file))
    }

    private fun gradlewInvocations(fixture: Fixture): List<String> {
        val log = File(fixture.state, "gradlew-args.log")
        return if (log.exists()) log.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    // -------------------------------------------------------------
    // C1: a signal during the run must not exit 0
    // -------------------------------------------------------------

    @ParameterizedTest(name = "SIG{0} -> exit {1}")
    @CsvSource("TERM, 143", "INT, 130")
    fun `C1 - signal during the stub baseline exits non-zero and never reports SUCCESS`(signal: String, expectedStatus: Int) {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val runOut = File(fixture.state, "run.out")
        // Job control (set -m) keeps SIGINT deliverable to the background script; without it a
        // non-interactive shell starts background jobs with SIGINT ignored.
        val wrapper = """
            set -m
            bash scripts/mutation-check.sh > "${shellPath(runOut)}" 2>&1 &
            pid=${'$'}!
            for _ in ${'$'}(seq 1 600); do
                [ -f "${'$'}STUB_STATE_DIR/baseline-started" ] && break
                sleep 0.1
            done
            if [ ! -f "${'$'}STUB_STATE_DIR/baseline-started" ]; then
                echo "WRAPPER: stub baseline never started"
                kill -KILL "${'$'}pid"
                exit 97
            fi
            kill -$signal "${'$'}pid"
            wait "${'$'}pid"
            echo "WRAPPER: script exit status ${'$'}?"
        """.trimIndent()
        val result = runShell(bash, fixture, stubEnv(fixture, mapOf("STUB_BASELINE_SLEEP" to "3")), wrapper)
        val scriptOutput = if (runOut.exists()) runOut.readText() else ""
        val all = "Wrapper output:\n${result.output}\nScript output:\n$scriptOutput"

        assertTrue(File(fixture.state, "baseline-started").exists(), "Stub baseline must have started. $all")
        assertTrue(
            result.output.contains("WRAPPER: script exit status $expectedStatus"),
            "SIG$signal during the run must make the script exit $expectedStatus. $all"
        )
        assertFalse(scriptOutput.contains("SUCCESS"), "A signalled run must never print SUCCESS. $all")
        assertFalse(scriptOutput.contains("Baseline PASSED"), "A signalled run must stop before reporting the baseline. $all")
    }

    // -------------------------------------------------------------
    // C2: a failing rm in cleanup must not change the exit code
    // -------------------------------------------------------------

    @Test
    fun `C2 - failing rm of the isolation directory keeps inject-compile-fault exit code 2 and reports the leftover`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val rmStub = File(fixture.bin, "rm").apply { writeText(STUB_RM) }
        makeExecutable(rmStub)
        // PATH is changed inside bash: Git for Windows' bin\bash.exe launcher prepends its own /usr/bin
        // to PATH, so a directory prepended from Java would come after the real rm.
        val wrapper = """
            export REAL_RM="${'$'}(command -v rm)"
            stub_bin="${'$'}(cd "${shellPath(fixture.bin)}" && pwd)"
            export PATH="${'$'}stub_bin:${'$'}PATH"
            [ "${'$'}(command -v rm)" = "${'$'}stub_bin/rm" ] || { echo "WRAPPER: rm stub is not first on PATH"; exit 98; }
            exec bash scripts/mutation-check.sh --inject-compile-fault
        """.trimIndent()

        val result = runShell(bash, fixture, stubEnv(fixture), wrapper)
        val isolationDir = File(fixture.state, "isolation-dir").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        try {
            assertTrue(isolationDir.isNotEmpty(), "Stub gradlew must have recorded the isolation directory. Output:\n${result.output}")
            assertTrue(
                result.output.contains("rm stub: refusing to remove isolation directory"),
                "The rm stub must have been hit for the isolation directory. Output:\n${result.output}"
            )
            assertTrue(result.output.contains("failed to compile"), "Compile fault must be reached. Output:\n${result.output}")
            assertEquals(2, result.exitCode, "A failing cleanup rm must not change exit code 2. Output:\n${result.output}")
            assertTrue(
                result.output.contains("could not remove temporary isolation directory") && result.output.contains(isolationDir),
                "The leftover isolation directory must be reported. Output:\n${result.output}"
            )
        } finally {
            if (isolationDir.isNotEmpty()) {
                runShell(bash, fixture, emptyMap(), "rm -rf '$isolationDir'")
            }
        }
    }

    @Test
    fun `C2 - valid control - without the rm stub the isolation directory is removed and exit code is 2`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val result = runScript(bash, fixture, stubEnv(fixture), "--inject-compile-fault")
        assertEquals(2, result.exitCode, "inject-compile-fault must exit 2. Output:\n${result.output}")
        val isolationDir = File(fixture.state, "isolation-dir").readText().trim()
        val check = runShell(bash, fixture, emptyMap(), "if [ -e '$isolationDir' ]; then echo present; else echo absent; fi")
        assertEquals("absent", check.output.trim(), "Isolation directory $isolationDir must be removed. Output:\n${result.output}")
        assertFalse(result.output.contains("could not remove"), "No leftover must be reported. Output:\n${result.output}")
    }

    // -------------------------------------------------------------
    // C3: CDPATH in the environment
    // -------------------------------------------------------------

    @Test
    fun `C3 - CDPATH set in the environment does not break path resolution`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val result = runScript(bash, fixture, stubEnv(fixture, mapOf("CDPATH" to ".")), "--inject-compile-fault")
        assertFalse(result.output.contains("No such file or directory"), "cd must not fail under CDPATH. Output:\n${result.output}")
        assertTrue(result.output.contains("Baseline PASSED"), "Baseline must run under CDPATH. Output:\n${result.output}")
        assertTrue(result.output.contains("failed to compile"), "Compile fault must be reached under CDPATH. Output:\n${result.output}")
        assertEquals(2, result.exitCode, "inject-compile-fault must exit 2 under CDPATH. Output:\n${result.output}")
    }

    // -------------------------------------------------------------
    // C4: tests-executed guard
    // -------------------------------------------------------------

    @Test
    fun `C4 - suite XML without a numeric tests attribute fails closed with exit code 4`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val result = runScript(bash, fixture, stubEnv(fixture, mapOf("STUB_SUITE_XML" to "many")))
        assertFalse(result.output.contains("PASS: Mutation M1 KILLED"), "M1 must not count as killed. Output:\n${result.output}")
        assertTrue(result.output.contains("FATAL: Mutation M1"), "The guard must report M1. Output:\n${result.output}")
        assertEquals(4, result.exitCode, "A non-numeric tests= value must fail closed with exit 4. Output:\n${result.output}")
    }

    @Test
    fun `C4 - valid control - suite XML with tests 0 still fails with exit code 4`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val result = runScript(bash, fixture, stubEnv(fixture, mapOf("STUB_SUITE_XML" to "0")))
        assertFalse(result.output.contains("PASS: Mutation M1 KILLED"), "M1 must not count as killed. Output:\n${result.output}")
        assertEquals(4, result.exitCode, "tests=0 must fail with exit 4. Output:\n${result.output}")
    }

    @Test
    fun `C4 - valid control - the same XML with a numeric tests attribute passes the guard and kills M1`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val result = runScript(bash, fixture, stubEnv(fixture, mapOf("STUB_SUITE_XML" to "52")))
        assertTrue(
            result.output.contains("PASS: Mutation M1 KILLED! (52 tests executed, failing cases: PACKAGE_MISMATCH_CASE_DIFFERENCE)"),
            "M1 must be killed with a numeric tests= value. Output:\n${result.output}"
        )
        // The fixture contains only the M1 target, so the run then stops at M2's pattern check.
        assertTrue(result.output.contains("FATAL: Pattern"), "Run must stop at M2's pattern check. Output:\n${result.output}")
        assertEquals(1, result.exitCode, "Missing M2 pattern must exit 1. Output:\n${result.output}")
    }

    // -------------------------------------------------------------
    // C5: the baseline must force a fresh run
    // -------------------------------------------------------------

    @Test
    fun `C5 - baseline gradlew invocation forces a fresh run`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val result = runScript(bash, fixture, stubEnv(fixture), "--inject-compile-fault")
        assertEquals(2, result.exitCode, "inject-compile-fault must exit 2. Output:\n${result.output}")
        val invocations = gradlewInvocations(fixture)
        assertEquals(2, invocations.size, "Expected baseline and compile invocations, got $invocations")
        val baseline = invocations[0].split(Regex("\\s+"))
        assertEquals("test", baseline[0], "First invocation must be the baseline test run, got ${invocations[0]}")
        assertTrue("--tests" !in baseline, "Baseline must run the full suite, got ${invocations[0]}")
        assertTrue("--rerun-tasks" in baseline, "Baseline must pass --rerun-tasks, got ${invocations[0]}")
        assertTrue("--no-build-cache" in baseline, "Baseline must pass --no-build-cache, got ${invocations[0]}")
        assertEquals("compileKotlin compileTestKotlin", invocations[1], "Second invocation must be the compile gate")
    }

    @Test
    fun `C5 - valid control - self-test evidence and exit-code contract are unchanged with the stub`() {
        val bash = requireBash()
        val fixture = buildFixture(bash)
        val result = runScript(bash, fixture, stubEnv(fixture), "--selftest-compile-fault")
        assertEquals(0, result.exitCode, "Self-test must exit 0. Output:\n${result.output}")
        assertTrue(result.output.contains("Baseline PASSED"), "Output:\n${result.output}")
        assertTrue(result.output.contains("Injecting deliberate Kotlin compilation fault"), "Output:\n${result.output}")
        assertTrue(result.output.contains("failed to compile"), "Output:\n${result.output}")
        assertTrue(
            result.output.contains("Self-test PASSED: fresh baseline ran, compile fault was reached and rejected with exit code (2)."),
            "Output:\n${result.output}"
        )
    }
}
