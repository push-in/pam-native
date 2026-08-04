package dev.pam.nativeapp.modules

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

internal class SmsModule(private val activity: Activity) : NativeModule {
    private val main = Handler(Looper.getMainLooper())

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        main.post {
            runCatching {
                when (method) {
                    "isAvailable" -> availability(completion)
                    "compose" -> compose(payload, completion)
                    else -> error("Unknown SMS method $method")
                }
            }.onFailure { error ->
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    (error.message ?: "SMS operation failed").toByteArray(),
                )
            }
        }
    }

    private fun availability(completion: ModuleCompletion) {
        val available = smsIntent(listOf("0"), "").resolveActivity(activity.packageManager) != null
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("available" to WireValue.Flag(available))),
        )
    }

    private fun compose(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val recipients = values.text("recipients").lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        require(recipients.isNotEmpty()) { "SMS requires at least one recipient" }
        require(recipients.size <= 50) { "SMS supports at most 50 recipients" }
        require(recipients.all { it.toByteArray().size <= 128 }) { "SMS recipient is too long" }
        val body = values.text("body")
        require(body.toByteArray().size <= 10_000) { "SMS body is too long" }
        val intent = smsIntent(recipients, body)
        require(intent.resolveActivity(activity.packageManager) != null) {
            "No SMS application is available"
        }
        activity.startActivity(intent)
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    companion object {
        internal fun smsIntent(recipients: List<String>, body: String): Intent =
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.fromParts("smsto", recipients.joinToString(";"), null)
                if (body.isNotEmpty()) putExtra("sms_body", body)
            }
    }

    private fun Map<String, WireValue>.text(key: String): String =
        (this[key] as? WireValue.Text)?.value ?: ""
}
