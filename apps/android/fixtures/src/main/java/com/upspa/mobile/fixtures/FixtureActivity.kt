package com.upspa.mobile.fixtures

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared behaviour for every controlled fixture screen.
 *
 * A fixture never reads, submits, logs, or persists the content of its fields. The submit button
 * only acknowledges the tap, so a demonstration cannot leak a filled value even by accident, and
 * a reviewer can tell from this one place that no screen in the module does.
 */
abstract class FixtureActivity : AppCompatActivity() {
    @get:LayoutRes
    protected abstract val layoutRes: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutRes)
        findViewById<View?>(R.id.fixture_submit)?.setOnClickListener {
            Toast.makeText(this, R.string.fixture_submit_noop, Toast.LENGTH_SHORT).show()
        }
    }
}
