package dev.pam.nativeapp.render

import android.util.Log
import android.widget.TextView
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue

/** TEMPORARY layout/ellipsis probe (tag PAMPROBE). Not for merge. */
internal object PamProbe {
    fun frame(kind: String, id: Long, nodeKind: Any?, properties: Map<PropKey, PropValue>, frame: Frame, parent: Frame?, h: SnappedPixelSpan, v: SnappedPixelSpan, density: Float) {
        val text = (properties[PropKey.TEXT] as? PropValue.Text)?.value?.take(24) ?: ""
        val label = (properties[PropKey.ACCESSIBILITY_LABEL] as? PropValue.Text)?.value?.take(24) ?: ""
        Log.d(
            "PAMPROBE",
            "$kind id=$id kind=$nodeKind text=[$text] a11y=[$label] pt=(${frame.x},${frame.y},${frame.width},${frame.height}) " +
                "parentPt=(${parent?.x},${parent?.y}) px=(${frame.x * density},${frame.y * density},${frame.width * density},${frame.height * density}) " +
                "snap=(dx=${h.offset},dy=${v.offset},w=${h.extent},h=${v.extent})",
        )
    }

    fun ellipsis(view: TextView, source: CharSequence, width: Int, result: CharSequence?) {
        val p = view.paint
        Log.d(
            "PAMPROBE",
            "ellipsis width=$width size=${p.textSize} face=${p.typeface} weight=${if (android.os.Build.VERSION.SDK_INT >= 28) p.typeface?.weight else -1} " +
                "letter=${p.letterSpacing} fake=${p.isFakeBoldText} full=${p.measureText(source.toString())} dots=${p.measureText("…")} " +
                "pad=${view.compoundPaddingLeft}/${view.compoundPaddingRight} src=[$source] out=[$result]",
        )
    }
}
