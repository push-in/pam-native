package dev.pam.nativeapp.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.net.URI

internal class AudioRecorderModule(private val context: Context) : NativeModule, AutoCloseable {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "start" -> start(completion)
                "stop" -> stop(completion)
                "cancel" -> cancel(completion)
                "discard" -> discard(payload, completion)
                else -> error("Unknown audio recorder method $method")
            }
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "Audio recorder operation failed").toByteArray(),
            )
        }
    }

    private fun start(completion: ModuleCompletion) {
        require(recorder == null) { "An audio recording is already active" }
        require(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "Microphone permission is required" }

        val file = File.createTempFile("pam-voice-", ".m4a", context.cacheDir)
        val next = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        output = file
        recorder = next
        startedAt = SystemClock.elapsedRealtime()
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun stop(completion: ModuleCompletion) {
        val active = recorder ?: error("No audio recording is active")
        val file = output ?: error("Audio recording output is unavailable")
        val durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        recorder = null
        output = null
        startedAt = 0L
        runCatching { active.stop() }.onFailure {
            active.reset()
            active.release()
            file.delete()
            throw IllegalStateException("Audio recording was too short", it)
        }
        active.reset()
        active.release()
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(
                mapOf(
                    "uri" to WireValue.Text(file.toURI().toString()),
                    "fileName" to WireValue.Text(file.name),
                    "mimeType" to WireValue.Text("audio/mp4"),
                    "durationMs" to WireValue.Integer(durationMs),
                    "size" to WireValue.Integer(file.length()),
                ),
            ),
        )
    }

    private fun cancel(completion: ModuleCompletion) {
        release(deleteOutput = true)
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun discard(payload: ByteArray, completion: ModuleCompletion) {
        val uri = (WireMap.decode(payload)["uri"] as? WireValue.Text)?.value
            ?: error("Audio recording URI is required")
        val file = File(URI(uri)).canonicalFile
        require(file.parentFile == context.cacheDir.canonicalFile && file.name.startsWith("pam-voice-")) {
            "Audio recording URI is outside the recorder cache"
        }
        if (file.exists()) require(file.delete()) { "Unable to delete audio recording" }
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun release(deleteOutput: Boolean) {
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        recorder = null
        if (deleteOutput) output?.delete()
        output = null
        startedAt = 0L
    }

    override fun close() {
        release(deleteOutput = true)
    }
}
