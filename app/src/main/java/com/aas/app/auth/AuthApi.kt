package com.aas.app.auth

import android.content.Context
import com.aas.app.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object AuthApi {
    sealed interface Result {
        data object Valid : Result
        data object Invalid : Result
        data class Error(val message: String) : Result
    }

    fun checkPassword(context: Context, password: String): Result {
        if (password.isBlank()) return Result.Invalid

        val endpoint = context.getString(R.string.auth_server_url).trim()
        if (endpoint.isBlank() || endpoint.contains("YOUR-DOMAIN", ignoreCase = true)) {
            return Result.Error(context.getString(R.string.auth_server_not_configured))
        }

        val url = runCatching { URL(endpoint) }.getOrElse {
            return Result.Error(context.getString(R.string.auth_bad_server_url))
        }

        val localDevelopment = url.host == "127.0.0.1" || url.host == "localhost"
        if (!url.protocol.equals("https", ignoreCase = true) && !localDevelopment) {
            return Result.Error(context.getString(R.string.auth_https_required))
        }

        val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrElse {
            return Result.Error(it.message ?: context.getString(R.string.auth_connection_error))
        }

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AAS-Android/1.3.0")

            val requestBody = JSONObject().put("password", password).toString()
                .toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(requestBody.size)
            connection.outputStream.use { it.write(requestBody) }

            val code = connection.responseCode
            val responseBody = readLimited(
                if (code in 200..299) connection.inputStream else connection.errorStream,
                maxChars = 16_384,
            )

            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val valid = runCatching { JSONObject(responseBody).optBoolean("valid", false) }
                        .getOrDefault(false)
                    if (valid) Result.Valid else Result.Invalid
                }
                code == 429 -> Result.Error(context.getString(R.string.auth_too_many_attempts))
                code in 500..599 -> Result.Error(context.getString(R.string.auth_server_error))
                else -> Result.Error(context.getString(R.string.auth_http_error, code))
            }
        } catch (t: Throwable) {
            Result.Error(t.message ?: context.getString(R.string.auth_connection_error))
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream?, maxChars: Int): String {
        if (stream == null) return ""
        val output = StringBuilder()
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            val buffer = CharArray(1024)
            while (output.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - output.length))
                if (read <= 0) break
                output.append(buffer, 0, read)
            }
        }
        return output.toString()
    }
}
