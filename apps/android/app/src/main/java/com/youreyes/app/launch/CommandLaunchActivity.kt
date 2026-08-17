package com.youreyes.app.launch

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Transient trampoline shown by a full-screen-intent notification so a
 * background-triggered command (call, navigation, Uber, music) can open its
 * real target screen even over a locked screen, without the user tapping
 * anything first — the same mechanism incoming-call and alarm apps use.
 *
 * Reads the real target [Intent] from [EXTRA_TARGET_INTENT], starts it, then
 * finishes itself immediately; it has no UI of its own.
 */
class CommandLaunchActivity : ComponentActivity() {

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
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        }

        @Suppress("DEPRECATION")
        val targetIntent = intent.getParcelableExtra<Intent>(EXTRA_TARGET_INTENT)
        if (targetIntent != null) {
            try {
                startActivity(targetIntent)
            } catch (exc: Exception) {
                // Swallowing this silently (the previous runCatching) made a
                // blocked launch look identical to a successful one: the action
                // still reported "succeeded" and nothing appeared in the log to
                // say the target screen never opened.
                Log.e(
                    TAG,
                    "startActivity failed for action=${targetIntent.action} data=${targetIntent.data}",
                    exc,
                )
            }
        }

        // Media-play-from-search intents commonly just queue the track paused
        // instead of auto-playing it; nudge the active media session to actually
        // start once the target app has had time to load and claim it.
        val playKeyDelayMs = intent.getLongExtra(EXTRA_SEND_PLAY_KEY_DELAY_MS, 0L)
        if (playKeyDelayMs > 0) {
            val appContext = applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            }, playKeyDelayMs)
        }

        finish()
    }

    companion object {
        private const val TAG = "CommandLaunchActivity"
        const val EXTRA_TARGET_INTENT = "target_intent"
        const val EXTRA_SEND_PLAY_KEY_DELAY_MS = "send_play_key_delay_ms"
    }
}
