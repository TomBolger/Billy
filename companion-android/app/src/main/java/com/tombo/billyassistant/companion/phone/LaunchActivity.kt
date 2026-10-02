package com.tombo.billyassistant.companion.phone

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Opens an app (Maps, music, ...) on behalf of the watch even when the phone
 * is locked: it wakes the screen, shows over the lock screen, asks Android to
 * unlock (fingerprint/face/PIN), then hands off to the real app.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        val target = targetIntent() ?: return finish()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            openAndFinish(target)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = openAndFinish(target)
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun openAndFinish(target: Intent) {
        runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun targetIntent(): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_TARGET)
    }

    companion object {
        private const val EXTRA_TARGET = "target"

        fun isLocked(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

        fun intentFor(context: Context, target: Intent): Intent =
            Intent(context, LaunchActivity::class.java)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
