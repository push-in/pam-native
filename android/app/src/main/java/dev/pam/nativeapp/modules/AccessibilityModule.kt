package dev.pam.nativeapp.modules

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import dev.pam.nativeapp.PamTextScale
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/** Screen-reader announcements, accessibility service state and the app text scale. */
internal class AccessibilityModule(private val context: Context) : NativeModule {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "announce" -> {
                    val text = (WireMap.decode(payload)["text"] as? WireValue.Text)?.value
                        ?: error("Announcement text is required")
                    require(text.isNotBlank() && text.length <= MAX_TEXT) { "Announcement text is invalid" }
                    completion.complete(
                        ModuleResultStatus.SUCCESS,
                        WireMap.encode(mapOf("delivered" to WireValue.Flag(announce(context, text)))),
                    )
                }
                "status" -> {
                    val manager = context.getSystemService(AccessibilityManager::class.java)
                    completion.complete(
                        ModuleResultStatus.SUCCESS,
                        WireMap.encode(
                            mapOf(
                                "enabled" to WireValue.Flag(manager?.isEnabled == true),
                                "touchExploration" to WireValue.Flag(manager?.isTouchExplorationEnabled == true),
                            ),
                        ),
                    )
                }
                "textScale" -> completion.complete(ModuleResultStatus.SUCCESS, textScaleSnapshot())
                "setTextScale" -> {
                    val values = WireMap.decode(payload)
                    val multiplier = (values["multiplier"] as? WireValue.Decimal)?.value?.toFloat()
                        ?: error("Text scale multiplier is required")
                    val cap = (values["maxSystemScale"] as? WireValue.Decimal)?.value?.toFloat() ?: 0f
                    require(PamTextScale.persist(context, multiplier, cap)) { "Text scale could not be persisted" }
                    completion.complete(ModuleResultStatus.SUCCESS, textScaleSnapshot())
                }
                else -> error("Unknown accessibility method $method")
            }
        }.onFailure {
            completion.complete(
                ModuleResultStatus.FAILURE,
                (it.message ?: "Accessibility operation failed").toByteArray(),
            )
        }
    }

    private fun textScaleSnapshot(): ByteArray {
        val (multiplier, cap) = PamTextScale.stored(context)
        val (appliedMultiplier, appliedCap) = PamTextScale.applied(context)
        return WireMap.encode(
            mapOf(
                "multiplier" to WireValue.Decimal(multiplier.toDouble()),
                "maxSystemScale" to WireValue.Decimal(cap.toDouble()),
                "appliedMultiplier" to WireValue.Decimal(appliedMultiplier.toDouble()),
                "appliedMaxSystemScale" to WireValue.Decimal(appliedCap.toDouble()),
                "systemScale" to WireValue.Decimal(context.resources.configuration.fontScale.toDouble()),
                "effectiveScale" to WireValue.Decimal(PamTextScale.effective(context).toDouble()),
            ),
        )
    }

    internal companion object {
        const val MAX_TEXT = 4_096

        /** Returns false when no accessibility service is listening. */
        fun announce(context: Context, text: String): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            if (!manager.isEnabled) return false
            val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            } else {
                @Suppress("DEPRECATION")
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            }
            event.className = AccessibilityModule::class.java.name
            event.packageName = context.packageName
            event.text.add(text)
            manager.sendAccessibilityEvent(event)
            return true
        }
    }
}
