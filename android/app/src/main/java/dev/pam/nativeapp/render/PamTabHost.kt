package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Platform tab strip with all scenes retained and no PHP work during swipes. */
internal class PamTabHost(context: Context) : FrameLayout(context) {
    private data class Item(val label: String, val badge: String?)

    private val content = FrameLayout(context)
    private val bar = LinearLayout(context)
    private var items: List<Item> = emptyList()
    private var selectedIndex = 1
    private var position = POSITION_BOTTOM
    private var activeColor = Color.BLACK
    private var inactiveColor = Color.GRAY
    private var barColor = Color.WHITE
    private var indicatorColor = Color.BLACK
    private var swipeEnabled = false
    private var touchStartX = 0f
    var onSelect: ((Int) -> Unit)? = null

    init {
        clipChildren = true
        super.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        super.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(64f)))
    }

    fun insertScene(view: View, index: Int) {
        content.addView(
            view,
            index.coerceIn(0, content.childCount),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        updateSelection()
    }

    fun configure(
        encodedItems: String,
        selectedIndex: Int,
        position: Int,
        activeColor: Int,
        inactiveColor: Int,
        barColor: Int,
        indicatorColor: Int,
        swipeEnabled: Boolean,
    ) {
        val decoded = runCatching {
            val array = JSONArray(encodedItems)
            List(array.length().coerceAtMost(32)) { index ->
                array.getJSONObject(index).let { value ->
                    Item(value.getString("label").take(64), value.optString("badge").takeIf { it.isNotEmpty() }?.take(12))
                }
            }
        }.getOrDefault(emptyList())
        val rebuild = decoded != items || position != this.position
        items = decoded
        this.selectedIndex = selectedIndex.coerceIn(1, items.size.coerceAtLeast(1))
        this.position = position.coerceIn(POSITION_BOTTOM, POSITION_RAIL)
        this.activeColor = activeColor
        this.inactiveColor = inactiveColor
        this.barColor = barColor
        this.indicatorColor = indicatorColor
        this.swipeEnabled = swipeEnabled
        if (rebuild) rebuildBar() else updateSelection()
        requestLayout()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled || position != POSITION_TOP || content.childCount < 2) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchStartX = event.x
        if (event.actionMasked == MotionEvent.ACTION_MOVE && kotlin.math.abs(event.x - touchStartX) > dp(12f)) return true
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            val delta = event.x - touchStartX
            val next = when {
                delta < -dp(48f) -> selectedIndex + 1
                delta > dp(48f) -> selectedIndex - 1
                else -> selectedIndex
            }.coerceIn(1, items.size.coerceAtLeast(1))
            selectTab(next)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val barHeight = dp(if (position == POSITION_TOP) 56f else 64f)
        val railWidth = dp(104f)
        when (position) {
            POSITION_TOP -> {
                bar.layout(0, 0, width, barHeight)
                content.layout(0, barHeight, width, height)
            }
            POSITION_RAIL -> {
                bar.layout(0, 0, railWidth, height)
                content.layout(railWidth, 0, width, height)
            }
            else -> {
                content.layout(0, 0, width, (height - barHeight).coerceAtLeast(0))
                bar.layout(0, (height - barHeight).coerceAtLeast(0), width, height)
            }
        }
    }

    private fun rebuildBar() {
        bar.removeAllViews()
        bar.orientation = if (position == POSITION_RAIL) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        bar.setBackgroundColor(barColor)
        items.forEachIndexed { index, item ->
            val label = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = if (position == POSITION_TOP) 14f else 12f
                minWidth = dp(48f)
                minHeight = dp(48f)
                maxLines = 2
                text = item.badge?.let { "${item.label}\n$it" } ?: item.label
                contentDescription = item.badge?.let { "${item.label}, $it" } ?: item.label
                setOnClickListener { selectTab(index + 1) }
            }
            bar.addView(
                label,
                LinearLayout.LayoutParams(
                    if (position == POSITION_RAIL) LayoutParams.MATCH_PARENT else 0,
                    if (position == POSITION_RAIL) 0 else LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
        }
        updateSelection()
    }

    internal fun selectTab(index: Int) {
        if (index !in 1..items.size) return
        selectedIndex = index
        updateSelection()
        onSelect?.invoke(index)
    }

    internal val activeSceneIndex: Int get() = selectedIndex

    private fun updateSelection() {
        for (index in 0 until content.childCount) {
            content.getChildAt(index).apply {
                visibility = if (index + 1 == selectedIndex) View.VISIBLE else View.GONE
                importantForAccessibility = if (index + 1 == selectedIndex) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                } else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        for (index in 0 until bar.childCount) {
            (bar.getChildAt(index) as? TextView)?.apply {
                isSelected = index + 1 == selectedIndex
                setTextColor(if (isSelected) activeColor else inactiveColor)
                setBackgroundColor(if (isSelected && position == POSITION_TOP) indicatorColor else Color.TRANSPARENT)
            }
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val POSITION_BOTTOM = 1
        const val POSITION_TOP = 2
        const val POSITION_RAIL = 3
    }
}
