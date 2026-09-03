package com.upspa.research.fixtures

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Jetpack Compose login fixture (research topic 10: real-application compatibility).
 *
 * Compose does NOT render EditText views: the whole screen is a single AndroidComposeView,
 * and autofill visibility is mediated by Compose's own autofill tree, not by the classic
 * view hierarchy. This fixture therefore has two arms, toggled by the launcher switch:
 *
 *  - Switch OFF (default): plain [OutlinedTextField]s with no autofill wiring. On the pinned
 *    Compose BOM (2024.12.01 / Compose UI 1.7.x) text fields are expected to be INVISIBLE to
 *    AutofillService providers — the key real-app compatibility finding to demonstrate.
 *  - Switch ON: the same fields wired through Compose's explicit autofill API
 *    ([AutofillNode] + [LocalAutofillTree]), which surfaces them as virtual autofill nodes
 *    carrying [AutofillType.Username]/[AutofillType.Password] (Tier 1 signals).
 *
 * Newer Compose versions (1.8+) replace this with `Modifier.semantics { contentType = ... }`
 * and enable autofill by default; re-running EXP-003 after a BOM bump is a planned
 * Phase 3 experiment.
 */
class ComposeLoginActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ComposeLoginScreen(
                    explicitAutofill = specHintsRequested,
                    onSubmit = ::signalCommit,
                )
            }
        }
    }
}

// Opt-in needed here too: AutofillType in optionalAutofill's signature makes the
// experimental marker propagate to call sites.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ComposeLoginScreen(explicitAutofill: Boolean, onSubmit: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Sign in (Jetpack Compose)",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = if (explicitAutofill) {
                "Arm B: explicit AutofillNode wiring (fields visible to autofill providers)"
            } else {
                "Arm A: default Compose text fields (no autofill wiring)"
            },
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username or email") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .optionalAutofill(explicitAutofill, AutofillType.Username) { username = it },
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .optionalAutofill(explicitAutofill, AutofillType.Password) { password = it },
        )

        Button(onClick = onSubmit) {
            Text("Sign in")
        }
    }
}

/**
 * Compose 1.7-era explicit autofill wiring: registers a virtual [AutofillNode] in the
 * composition-wide autofill tree, keeps its bounding box in sync with layout, and
 * requests/cancels platform autofill as focus moves. No-op when [enabled] is false so
 * Arm A stays completely unwired.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.optionalAutofill(
    enabled: Boolean,
    autofillType: AutofillType,
    onFill: (String) -> Unit,
): Modifier {
    if (!enabled) return this

    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val node = remember { AutofillNode(autofillTypes = listOf(autofillType), onFill = onFill) }
    autofillTree += node

    return this
        .onGloballyPositioned { coordinates -> node.boundingBox = coordinates.boundsInWindow() }
        .onFocusChanged { state ->
            autofill?.let {
                if (state.isFocused) {
                    it.requestAutofillForNode(node)
                } else {
                    it.cancelAutofillForNode(node)
                }
            }
        }
}
