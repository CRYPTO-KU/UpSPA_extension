package com.upspa.mobile.fixtures

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Launcher for the controlled Autofill fixtures. */
class FixtureMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            // The menu itself has no input, so it must not appear in an autofill request.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(
                TextView(context).apply {
                    text = getString(R.string.menu_title)
                    textSize = 22f
                },
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.menu_subtitle)
                    setPadding(0, 0, 0, 32)
                },
            )
        }

        FIXTURE_SCENARIOS.forEach { (labelRes, target) ->
            column.addView(
                Button(this).apply {
                    text = getString(labelRes)
                    setOnClickListener { startActivity(Intent(context, target)) }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        setContentView(ScrollView(this).apply { addView(column) })
    }
}
