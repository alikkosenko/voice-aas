package com.aas.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.aas.app.auth.AuthApi
import com.aas.app.auth.AuthState
import com.aas.app.databinding.ActivityLoginBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = AppPrefs(this)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(prefs.languageTag))
        super.onCreate(savedInstanceState)

        if (AuthState.isAuthorized(this)) {
            openMainScreen()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buttonLogin.setOnClickListener { submitPassword() }
        binding.inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitPassword()
                true
            } else false
        }
    }

    private fun submitPassword() {
        val password = binding.inputPassword.text?.toString().orEmpty()
        if (password.isBlank()) {
            binding.passwordLayout.error = getString(R.string.auth_enter_password)
            return
        }

        binding.passwordLayout.error = null
        setBusy(true)
        executor.execute {
            val result = AuthApi.checkPassword(applicationContext, password)
            // Avoid keeping the password String in any field or SharedPreferences.
            if (destroyed) return@execute
            mainHandler.post {
                if (destroyed) return@post
                when (result) {
                    AuthApi.Result.Valid -> {
                        AuthState.grant(applicationContext)
                        binding.inputPassword.text?.clear()
                        openMainScreen()
                    }
                    AuthApi.Result.Invalid -> {
                        setBusy(false)
                        binding.passwordLayout.error = getString(R.string.auth_wrong_password)
                        binding.inputPassword.text?.clear()
                        binding.inputPassword.requestFocus()
                    }
                    is AuthApi.Result.Error -> {
                        setBusy(false)
                        binding.textAuthStatus.text = result.message
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.buttonLogin.isEnabled = !busy
        binding.inputPassword.isEnabled = !busy
        binding.progressAuth.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.textAuthStatus.text = if (busy) getString(R.string.auth_checking) else ""
    }

    private fun openMainScreen() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }
}
