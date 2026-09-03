package com.upspa.research.fixtures

import android.os.Bundle
import android.webkit.WebView

/**
 * WebView login fixture (research topic 5). The page carries one spec-compliant form
 * (autocomplete attributes -> HtmlInfo Tier 1) and one heuristic-only form. JavaScript stays
 * disabled: the forms are static and never submit anywhere.
 */
class WebViewLoginActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        findViewById<WebView>(R.id.webView).loadUrl("file:///android_asset/login.html")
    }
}
