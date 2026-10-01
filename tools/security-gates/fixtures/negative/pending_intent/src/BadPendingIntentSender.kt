package com.upspa.mobile.fixture.negative

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object BadPendingIntentSender {
    fun send(context: Context) {
        val target = Intent("com.upspa.mobile.fixture.negative.action")
        val pending = PendingIntent.getBroadcast(context, 0, target, 0)
        pending.send()
    }
}
