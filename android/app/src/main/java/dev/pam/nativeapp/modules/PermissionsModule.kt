package dev.pam.nativeapp.modules

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

internal class PermissionsModule(private val activity: PamActivity) : NativeModule {
    private val history = activity.getSharedPreferences("pam-permissions", 0)

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "status" -> result(kind(payload), completion)
                "request" -> request(kind(payload), completion)
                "openSettings" -> {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null),
                        ),
                    )
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                else -> error("Unknown permissions method $method")
            }
        }.onFailure { completion.failure(it.message ?: "Permission operation failed") }
    }

    private fun request(kind: Int, completion: ModuleCompletion) {
        if (kind == KIND_PHOTOS) {
            history.edit().putBoolean("photos", true).apply()
            activity.requestPamPermissions(photoPermissions()) {
                result(kind, completion)
            }
            return
        }
        if (kind == KIND_LOCATION) {
            history.edit().putBoolean("location", true).apply()
            activity.requestPamPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            ) { result(kind, completion) }
            return
        }
        val permission = permission(kind)
        if (permission == null) {
            result(kind, completion)
            return
        }
        history.edit().putBoolean(permission, true).apply()
        activity.requestPamPermission(permission) { result(kind, completion) }
    }

    private fun result(kind: Int, completion: ModuleCompletion) {
        if (kind == KIND_PHOTOS) {
            photosResult(completion)
            return
        }
        if (kind == KIND_LOCATION) {
            val fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val coarse = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val asked = history.getBoolean("location", false)
            val canAskAgain = !asked ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            val status = when {
                fine -> STATUS_GRANTED
                coarse -> STATUS_LIMITED
                canAskAgain -> STATUS_DENIED
                else -> STATUS_BLOCKED
            }
            completion.decision(status, canAskAgain)
            return
        }
        val permission = permission(kind)
        val granted = permission == null ||
            activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val asked = permission != null && history.getBoolean(permission, false)
        val canAskAgain = permission != null &&
            (!asked || activity.shouldShowRequestPermissionRationale(permission))
        val status = when {
            granted -> STATUS_GRANTED
            canAskAgain -> STATUS_DENIED
            else -> STATUS_BLOCKED
        }
        completion.decision(status, canAskAgain)
    }

    private fun photosResult(completion: ModuleCompletion) {
        val permissions = photoPermissions()
        val granted = permissions.count {
            activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val full = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED &&
                    activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED
            }
            else -> granted == permissions.size
        }
        val asked = history.getBoolean("photos", false)
        val canAskAgain = !asked || permissions.any(activity::shouldShowRequestPermissionRationale)
        val status = when {
            full -> STATUS_GRANTED
            granted > 0 -> STATUS_LIMITED
            canAskAgain -> STATUS_DENIED
            else -> STATUS_BLOCKED
        }
        completion.decision(status, canAskAgain)
    }

    private fun photoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun permission(kind: Int): String? = when (kind) {
        KIND_CAMERA -> Manifest.permission.CAMERA
        KIND_MICROPHONE -> Manifest.permission.RECORD_AUDIO
        KIND_PHOTOS -> error("Photos use a versioned grouped permission request")
        KIND_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                null
            }
        KIND_LOCATION -> error("Location uses a grouped permission request")
        KIND_CONTACTS -> Manifest.permission.READ_CONTACTS
        else -> error("Unknown permission kind $kind")
    }

    private fun kind(payload: ByteArray): Int =
        ((WireMap.decode(payload)["kind"] as? WireValue.Integer)?.value
            ?: error("Missing permission kind")).toInt()

    private fun ModuleCompletion.failure(message: String) {
        complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }

    private fun ModuleCompletion.decision(status: Int, canAskAgain: Boolean) {
        complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf(
                "status" to WireValue.Integer(status.toLong()),
                "canAskAgain" to WireValue.Flag(canAskAgain),
            )),
        )
    }

    private companion object {
        const val KIND_CAMERA = 1
        const val KIND_MICROPHONE = 2
        const val KIND_PHOTOS = 3
        const val KIND_NOTIFICATIONS = 4
        const val KIND_LOCATION = 5
        const val KIND_CONTACTS = 6
        const val STATUS_GRANTED = 1
        const val STATUS_DENIED = 2
        const val STATUS_BLOCKED = 3
        const val STATUS_LIMITED = 4
    }
}
