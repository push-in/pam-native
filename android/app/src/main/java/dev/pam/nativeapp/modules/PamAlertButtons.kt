package dev.pam.nativeapp.modules

import android.Manifest
import android.os.Build
import dev.pam.nativeapp.protocol.WireValue

internal data class PamAlertButton(val label: String, val style: Int)

/** Which button of an `Alert::choose()` list each AlertDialog slot shows (null = unused). */
internal data class PamAlertSlots(val positive: Int?, val negative: Int?, val neutral: Int?)

/**
 * Pure decoding of the optional multi-button payload of the `alert` operation:
 * `buttonCount` plus `button<i>Label`/`button<i>Style` (1 default, 2 cancel,
 * 3 destructive). A payload without `buttonCount` is the legacy single-OK alert.
 */
internal object PamAlertButtons {
    const val STYLE_DEFAULT = 1
    const val STYLE_CANCEL = 2
    const val STYLE_DESTRUCTIVE = 3
    const val MAX_BUTTONS = 3
    const val DISMISSED = -1

    fun decode(values: Map<String, WireValue>): List<PamAlertButton> {
        val count = (values["buttonCount"] as? WireValue.Integer)?.value?.toInt() ?: return emptyList()
        require(count in 1..MAX_BUTTONS) { "Alert supports between 1 and $MAX_BUTTONS buttons" }
        val buttons = (0 until count).map { index ->
            val label = (values["button${index}Label"] as? WireValue.Text)?.value
                ?: error("Missing alert button label $index")
            require(label.isNotBlank()) { "Alert button labels cannot be empty" }
            val style = ((values["button${index}Style"] as? WireValue.Integer)?.value ?: STYLE_DEFAULT.toLong()).toInt()
            require(style in STYLE_DEFAULT..STYLE_DESTRUCTIVE) { "Unknown alert button style $style" }
            PamAlertButton(label, style)
        }
        require(buttons.count { it.style == STYLE_CANCEL } <= 1) { "Alert supports one cancel button" }
        return buttons
    }

    /** The cancel button is the negative one; the others fill positive, then neutral, then negative. */
    fun slots(buttons: List<PamAlertButton>): PamAlertSlots {
        val cancel = buttons.indexOfFirst { it.style == STYLE_CANCEL }.takeIf { it >= 0 }
        val others = buttons.indices.filter { it != cancel }
        return PamAlertSlots(
            positive = others.getOrNull(0),
            negative = cancel ?: others.getOrNull(2),
            neutral = others.getOrNull(1),
        )
    }
}

/** Platform permissions whose rationale flag answers `Permissions::shouldShowRationale()`. */
internal object PermissionRationalePermissions {
    // Permission names are plain strings; each branch is gated by [sdk].
    @android.annotation.SuppressLint("InlinedApi")
    fun forKind(kind: Int, sdk: Int): List<String> = when (kind) {
        1 -> listOf(Manifest.permission.CAMERA)
        2 -> listOf(Manifest.permission.RECORD_AUDIO)
        3 -> when {
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            sdk >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        4 -> if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        5 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        6 -> listOf(Manifest.permission.READ_CONTACTS)
        7 -> if (sdk >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        8 -> emptyList() // Special app access: no runtime dialog, so no rationale.
        9 -> listOf(Manifest.permission.READ_PHONE_STATE)
        else -> error("Unknown permission kind $kind")
    }
}
