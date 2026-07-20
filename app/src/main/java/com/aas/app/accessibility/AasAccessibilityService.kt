package com.aas.app.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.aas.app.AppPrefs
import com.aas.app.runtime.AasRuntime
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Steering-wheel key filter built from BYDMate's SteeringWheelKeyService logic.
 *
 * The real liveness flag is [isConnected]. Merely seeing the component in
 * Settings.Secure does not prove that Android has actually bound the service.
 */
class AasAccessibilityService : AccessibilityService() {
    private lateinit var prefs: AppPrefs
    private var listeningOverlay: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        AasRuntime.requireInitialized(this)
        prefs = AasRuntime.prefs

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info

        instance = this
        isConnected = true
        // Attach the overlay window while the accessibility service is starting.
        // Later activations only reveal an already attached view, avoiding the
        // WindowManager.addView() delay after a steering-wheel button press.
        ensureVoiceOverlayCreated()
        if (overlayRequested) showVoiceOverlayInternal(overlayText, overlayColor)
        AasRuntime.voice.preload()
        prefs.lastResult = localized("Accessibility подключён; кнопка руля готова", "Accessibility підключено; кнопка керма готова")
        Log.i(TAG, "connected; steering-wheel key filter active")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return try {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            val firstDown = isDown && event.repeatCount == 0

            if (learnMode) {
                // While learning, consume every edge so the native BYD action does
                // not fire. Only the first DOWN edge decides capture/reject.
                if (!firstDown) {
                    true
                } else {
                    when (learnDecision(event.keyCode, isDown = true)) {
                        LearnAction.CAPTURE -> {
                            capturedKey.value = CaptureResult(event.keyCode, assignable = true)
                            learnMode = false
                            Log.i(TAG, "captured keyCode=${event.keyCode}")
                            true
                        }
                        LearnAction.REJECT -> {
                            capturedKey.value = CaptureResult(event.keyCode, assignable = false)
                            true
                        }
                        LearnAction.CONSUME -> true
                    }
                }
            } else {
                val enabled = prefs.enabled
                val voiceKey = prefs.activationKeyCode
                if (!enabled || event.keyCode != voiceKey) {
                    false
                } else if (!firstDown) {
                    // Consume UP and repeated DOWN packets without toggling again.
                    true
                } else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        AasRuntime.voice.toggle()
                    } else {
                        prefs.lastResult = localized("Нет разрешения на микрофон", "Немає дозволу на мікрофон")
                    }
                    true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onKeyEvent failed for key=${event.keyCode}", t)
            runCatching { AasRuntime.prefs.lastResult = localized("Ошибка кнопки руля: ${t.message}", "Помилка кнопки керма: ${t.message}") }
            false
        }
    }


    private fun localized(russian: String, ukrainian: String): String =
        if (prefs.languageTag.startsWith("uk", ignoreCase = true)) ukrainian else russian

    private fun ensureVoiceOverlayCreated(): TextView? {
        listeningOverlay?.let { return it }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val pill = TextView(this).apply {
            text = overlayText
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(11), dp(24), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(overlayColor)
            }
            elevation = dp(10).toFloat()
            alpha = 0f
            visibility = View.INVISIBLE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(20)
        }

        return runCatching {
            windowManager.addView(pill, params)
            listeningOverlay = pill
            pill
        }.onFailure {
            Log.w(TAG, "Unable to pre-attach voice overlay", it)
        }.getOrNull()
    }

    private fun showVoiceOverlayNow(text: String, color: Int) {
        val pill = ensureVoiceOverlayCreated() ?: return
        pill.animate().cancel()
        pill.text = text
        (pill.background as? GradientDrawable)?.setColor(color)
        pill.alpha = 1f
        pill.visibility = View.VISIBLE
    }

    private fun showVoiceOverlayInternal(text: String, color: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showVoiceOverlayNow(text, color)
        } else {
            mainHandler.postAtFrontOfQueue { showVoiceOverlayNow(text, color) }
        }
    }

    private fun hideListeningOverlayInternal() {
        val hide = Runnable {
            listeningOverlay?.let { view ->
                view.animate().cancel()
                view.alpha = 0f
                view.visibility = View.INVISIBLE
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) hide.run()
        else mainHandler.postAtFrontOfQueue(hide)
    }

    private fun removeVoiceOverlayInternal() {
        val remove = Runnable {
            val view = listeningOverlay
            listeningOverlay = null
            if (view != null) {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                runCatching { windowManager.removeViewImmediate(view) }
                    .onFailure { Log.w(TAG, "Unable to remove voice overlay", it) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run()
        else mainHandler.postAtFrontOfQueue(remove)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        removeVoiceOverlayInternal()
        instance = null
        isConnected = false
        Log.i(TAG, "unbound; key filter inactive")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeVoiceOverlayInternal()
        instance = null
        isConnected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AasSteeringKeySvc"

        @Volatile var isConnected: Boolean = false
            private set
        @Volatile var instance: AasAccessibilityService? = null
            private set

        @Volatile var learnMode: Boolean = false
        @Volatile private var overlayRequested: Boolean = false
        @Volatile private var overlayText: String = "●  Слушаю…"
        @Volatile private var overlayColor: Int = Color.rgb(30, 145, 72)

        fun showPreparingOverlay() = showVoiceOverlay(
            overlayLabel("●  Подготавливаю…", "●  Готую…"), Color.rgb(50, 105, 180)
        )
        fun showListeningOverlay() = showVoiceOverlay(
            overlayLabel("●  Слушаю…", "●  Слухаю…"), Color.rgb(30, 145, 72)
        )
        fun showRecognizingOverlay() = showVoiceOverlay(
            overlayLabel("●  Распознаю…", "●  Розпізнаю…"), Color.rgb(193, 119, 17)
        )
        fun showExecutingOverlay() = showVoiceOverlay(
            overlayLabel("●  Выполняю…", "●  Виконую…"), Color.rgb(111, 78, 170)
        )

        private fun overlayLabel(russian: String, ukrainian: String): String = runCatching {
            if (AasRuntime.prefs.languageTag.startsWith("uk", ignoreCase = true)) ukrainian else russian
        }.getOrDefault(russian)

        private fun showVoiceOverlay(text: String, color: Int) {
            overlayRequested = true
            overlayText = text
            overlayColor = color
            instance?.showVoiceOverlayInternal(text, color)
        }

        fun hideListeningOverlay() {
            overlayRequested = false
            instance?.hideListeningOverlayInternal()
        }
        data class CaptureResult(val keyCode: Int, val assignable: Boolean)
        val capturedKey = MutableStateFlow<CaptureResult?>(null)
    }
}

// Same safety exclusions used by BYDMate for button learning.
private val NON_ASSIGNABLE_KEYCODES: Set<Int> = setOf(
    24, // volume up
    25, // volume down
    26, // power
    4,  // back
    3,  // home
    82, // menu
    5,  // call
    6,  // end call
    310, // 360-view / parking camera
    309, // cluster carousel
)

private fun isAssignable(keyCode: Int): Boolean = keyCode !in NON_ASSIGNABLE_KEYCODES

private enum class LearnAction { CAPTURE, REJECT, CONSUME }
private fun learnDecision(keyCode: Int, isDown: Boolean): LearnAction {
    if (!isDown) return LearnAction.CONSUME
    return if (isAssignable(keyCode)) LearnAction.CAPTURE else LearnAction.REJECT
}
