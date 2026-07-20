package com.aas.app.auth

import android.content.Context
import android.content.SharedPreferences
import com.aas.app.R

/**
 * Stores only the fact and expiry time of a successful remote password check.
 * The password itself is never written to disk.
 *
 * Device-protected storage is used so BootReceiver can read the authorization
 * state after LOCKED_BOOT_COMPLETED on BYD/DiLink head units.
 */
object AuthState {
    private const val FILE_NAME = "aas_remote_auth"
    private const val KEY_VALID_UNTIL = "valid_until_epoch_ms"

    fun isAuthorized(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val validUntil = preferences(context).getLong(KEY_VALID_UNTIL, 0L)
        return validUntil == Long.MAX_VALUE || validUntil > nowMs
    }

    fun grant(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val hours = context.resources.getInteger(R.integer.auth_validity_hours)
        val validUntil = if (hours <= 0) {
            Long.MAX_VALUE
        } else {
            val duration = hours.toLong() * 60L * 60L * 1000L
            if (Long.MAX_VALUE - nowMs < duration) Long.MAX_VALUE else nowMs + duration
        }
        // Synchronous commit is intentional: BootReceiver/service may start immediately.
        preferences(context).edit().putLong(KEY_VALID_UNTIL, validUntil).commit()
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().commit()
    }

    private fun preferences(context: Context): SharedPreferences {
        val deviceContext = context.createDeviceProtectedStorageContext()
        return deviceContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }
}
