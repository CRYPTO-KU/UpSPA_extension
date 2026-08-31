package com.upspa.mobile.fixtures

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Controlled cross-package login form for the walking skeleton. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val username = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.fixture_username)
            inputType = InputType.TYPE_CLASS_TEXT
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        val password = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.fixture_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        val submit = Button(this).apply {
            text = getString(R.string.fixture_submit)
            setOnClickListener {
                // The fixture never submits or logs field values.
                Toast.makeText(context, "Fixture only: nothing was submitted", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(context).apply { text = getString(R.string.fixture_title) })
                addView(username, matchWidth())
                addView(password, matchWidth())
                addView(submit)
            },
        )
    }

    private fun matchWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
