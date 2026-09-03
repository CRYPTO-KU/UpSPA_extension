package com.upspa.research.fixtures

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automated autofill experiments (EXP-001..003).
 *
 * Why the mixed toolkit: the autofill dropdown, the dataset entries, and the provider's
 * AuthActivity are all rendered OUTSIDE this app's process (system UI / provider package),
 * so Espresso cannot see them — UIAutomator drives those surfaces, while Espresso drives
 * the fixture's own views and assertions.
 *
 * Preconditions (enforced or documented in [setUp]):
 *  1. The provider APK is installed:  ./gradlew :autofill-provider:installDebug
 *  2. The device is an emulator WITHOUT a lock screen or enrolled biometrics, so the
 *     provider's research bypass button is available. Devices with enrolled credentials
 *     require manual authentication and are out of scope for the automated run.
 *  3. Recommended launch:  ./gradlew :fixtures:connectedDebugAndroidTest
 *     (Managed devices do not auto-install the provider APK; use a connected emulator.)
 *
 * Evidence: each experiment emits structured observation lines under the logcat tag
 * `UpSpaExperiment`; collect with `adb logcat -s UpSpaExperiment UpSpaAutofill UpSpaLatency`
 * and attach to docs/android-credential-research/experiment-log.md.
 *
 * Secret hygiene: assertions only ever match the FAKE- prefixes; no real credentials exist
 * anywhere in this suite.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AutofillExperimentTest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Instrumentation shell commands run as the `shell` uid, which holds
        // WRITE_SECURE_SETTINGS — so the test can point the platform at the research
        // provider without any manual settings navigation.
        device.executeShellCommand("settings put secure autofill_service $PROVIDER_SERVICE")

        val installedPackages = device.executeShellCommand("pm list packages $PROVIDER_PACKAGE")
        assertTrue(
            "Provider APK is not installed on this device. " +
                "Run: ./gradlew :autofill-provider:installDebug",
            installedPackages.contains(PROVIDER_PACKAGE),
        )
    }

    // ------------------------------------------------------------------------------------
    // EXP-001 — Baseline XML login: full locked-entry round trip.
    // Hypothesis: heuristic (Tier 2/3) classification of the unhinted XML login screen is
    // sufficient for the locked entry to appear, and the auth flow fills FAKE values.
    // ------------------------------------------------------------------------------------
    @Test
    fun exp001_baselineXmlLogin_fullLockedFillRoundTrip() {
        launchFixture(LoginActivity::class.java).use {
            // Close the IME immediately: on API 34 the keyboard often overlays the
            // autofill dropdown, so the next UIAutomator click can miss the locked
            // entry or the dataset row (the 2026-08-28 connectedDebugAndroidTest
            // failure: Espresso saw an empty username after the dataset click).
            onView(withId(R.id.loginUsername)).perform(click(), closeSoftKeyboard())

            val lockedEntry = device.wait(
                Until.findObject(By.textContains(LOCKED_ENTRY_TEXT)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull(
                "Locked autofill entry never appeared. Is the provider enabled and the " +
                    "screen classified? Check `adb logcat -s UpSpaAutofill`.",
                lockedEntry,
            )
            observe("EXP-001", "locked entry shown for unhinted XML login screen")
            lockedEntry.click()

            // Provider AuthActivity (cross-package): use the research bypass.
            val bypassButton = device.wait(
                Until.findObject(By.textContains(BYPASS_BUTTON_TEXT)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull(
                "Research bypass button not shown. Run on an emulator without a lock " +
                    "screen, or authenticate manually and re-run.",
                bypassButton,
            )
            bypassButton.click()

            // After authentication the provider returns the unlocked FillResponse; the
            // system re-shows the dropdown with the FAKE dataset entry.
            val datasetEntry = device.wait(
                Until.findObject(By.textContains(DATASET_ENTRY_PREFIX)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull("Dataset entry not shown after authentication", datasetEntry)
            datasetEntry.click()

            // Autofill writes into the views asynchronously after the dataset tap.
            // Immediate Espresso checks race the framework; poll until the FAKE
            // prefixes land (manual Logcat run already proved the fill path).
            waitUntilTextStartsWith(R.id.loginUsername, "FAKE-user-")
            waitUntilTextStartsWith(R.id.loginPassword, "FAKE-pw-")
            observe("EXP-001", "both fields filled with FAKE values — round trip complete")
        }
    }

    // ------------------------------------------------------------------------------------
    // EXP-002 — Split login: fill offers on both steps of a multi-screen flow.
    // Hypothesis: the identifier-only step and the password-only step each produce a fill
    // offer on their own (Tier 3 treats both as LOGIN). Cross-step linkage via
    // FillContext history / client state is a Phase 3 follow-up on top of this stub.
    // ------------------------------------------------------------------------------------
    @Test
    fun exp002_splitLogin_lockedEntryOfferedOnBothSteps() {
        launchFixture(SplitLoginUsernameActivity::class.java).use {
            onView(withId(R.id.splitUsername)).perform(click())
            val step1Offer = device.wait(
                Until.findObject(By.textContains(LOCKED_ENTRY_TEXT)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull("No autofill offer on the identifier-only step", step1Offer)
            observe("EXP-002", "offer shown on step 1 (identifier-only screen)")

            // Dismiss the system fill UI so the in-app button is tappable.
            device.pressBack()
            onView(withId(R.id.splitNext)).perform(click())

            onView(withId(R.id.splitPassword)).perform(click())
            val step2Offer = device.wait(
                Until.findObject(By.textContains(LOCKED_ENTRY_TEXT)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull("No autofill offer on the password-only step", step2Offer)
            observe("EXP-002", "offer shown on step 2 (password-only screen)")

            // Phase 3 extension point: assert that the provider's fillContexts for the
            // step-2 request include step 1's structure (multi-screen continuity), and
            // exercise setClientState round-tripping.
        }
    }

    // ------------------------------------------------------------------------------------
    // EXP-003 — Compose login: autofill visibility, default vs explicit wiring.
    // Arm A hypothesis (Compose BOM 2024.12.01 / UI 1.7.x): DEFAULT Compose text fields are
    // invisible to AutofillService providers — no offer appears. Recorded as an observation
    // rather than a hard assertion because it is exactly the version-dependent behavior the
    // experiment tracks (newer Compose enables autofill by default).
    // Arm B: explicit AutofillNode wiring must surface the fields (hard assertion).
    // ------------------------------------------------------------------------------------
    @Test
    fun exp003_composeLogin_defaultVsExplicitAutofillVisibility() {
        // Arm A: default Compose text fields.
        launchFixture(ComposeLoginActivity::class.java).use {
            val usernameField = device.wait(
                Until.findObject(By.textContains(COMPOSE_USERNAME_LABEL)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull("Compose login screen did not render", usernameField)
            usernameField.click()

            val offer = device.wait(
                Until.findObject(By.textContains(LOCKED_ENTRY_TEXT)),
                NEGATIVE_OBSERVATION_TIMEOUT_MS,
            )
            observe(
                "EXP-003",
                "Arm A (default Compose fields): autofill offer shown = ${offer != null} " +
                    "(hypothesis for Compose 1.7: false)",
            )
        }

        // Arm B: explicit AutofillNode wiring (launcher switch equivalent).
        launchFixture(ComposeLoginActivity::class.java, specHints = true).use {
            val usernameField = device.wait(
                Until.findObject(By.textContains(COMPOSE_USERNAME_LABEL)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull("Compose login screen (Arm B) did not render", usernameField)
            usernameField.click()

            val offer = device.wait(
                Until.findObject(By.textContains(LOCKED_ENTRY_TEXT)),
                FILL_UI_TIMEOUT_MS,
            )
            assertNotNull(
                "Explicitly wired Compose fields must be visible to the provider " +
                    "(AutofillNode -> virtual view structure)",
                offer,
            )
            observe("EXP-003", "Arm B (explicit AutofillNode wiring): locked entry shown")
        }
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private fun <T : FixtureActivity> launchFixture(
        activityClass: Class<T>,
        specHints: Boolean = false,
    ): ActivityScenario<T> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), activityClass)
            .putExtra(FixtureActivity.EXTRA_SPEC_HINTS, specHints)
        return ActivityScenario.launch(intent)
    }

    /** Structured observation line for the evidence package (experiment-log.md). */
    private fun observe(experiment: String, message: String) {
        Log.i(OBSERVATION_TAG, "$experiment: $message")
    }

    /**
     * Polls an in-app EditText until its text starts with [prefix], or fails with the
     * last Espresso mismatch. Needed because AutofillManager applies dataset values
     * after the dataset click returns to the test thread.
     */
    private fun waitUntilTextStartsWith(viewId: Int, prefix: String) {
        val deadline = SystemClock.uptimeMillis() + FILL_UI_TIMEOUT_MS
        var lastError: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withId(viewId)).check(matches(withText(startsWith(prefix))))
                return
            } catch (t: Throwable) {
                lastError = t
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }
        throw lastError ?: AssertionError("Timed out waiting for view $viewId to start with $prefix")
    }

    private companion object {
        const val PROVIDER_PACKAGE = "com.upspa.research.provider"
        const val PROVIDER_SERVICE = "$PROVIDER_PACKAGE/.UpSpaAutofillService"

        /** Matches the locked-entry presentation (item_locked_entry.xml in the provider). */
        const val LOCKED_ENTRY_TEXT = "UpSPA Research"

        /** Matches the research bypass button in the provider's AuthActivity. */
        const val BYPASS_BUTTON_TEXT = "Research bypass"

        /** Matches the unlocked dataset presentation ("Fill as FAKE-user-... (fake)"). */
        const val DATASET_ENTRY_PREFIX = "Fill as FAKE-user-"

        /** Compose field label (semantics text, reachable by UIAutomator). */
        const val COMPOSE_USERNAME_LABEL = "Username or email"

        const val OBSERVATION_TAG = "UpSpaExperiment"
        const val FILL_UI_TIMEOUT_MS = 12_000L
        const val NEGATIVE_OBSERVATION_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
