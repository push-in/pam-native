package dev.pam.nativeapp.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val PAM_PROTOCOL_VERSION = 1
private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
private const val MAX_MUTATIONS = 800_000
private const val MAX_PROPERTIES = 128
private const val MAX_VALUE_BYTES = 1024 * 1024

enum class NodeKind(val value: Int) {
    SCREEN(1),
    COLUMN(2),
    ROW(3),
    TEXT(4),
    BUTTON(5),
    INPUT(6),
    IMAGE(7),
    SCROLL(8),
    LIST(9),
    SPACER(10),
    VIEW(11),
    PRESSABLE(12),
    ACTIVITY_INDICATOR(13),
    SWITCH(14),
    MODAL(15),
    IMAGE_BACKGROUND(16),
    KEYBOARD_AVOIDING_VIEW(17),
    SECTION_LIST(18),
    REFRESH_CONTROL(19),
    STATUS_BAR(20),
    SAFE_AREA_VIEW(21),
    DRAWER_LAYOUT(22),
    INPUT_ACCESSORY_VIEW(23),
    CUSTOM_VIEW(24);

    companion object {
        fun from(value: Int): NodeKind =
            entries.firstOrNull { it.value == value }
                ?: throw ProtocolException("Unknown node kind $value")
    }
}

enum class PropKey(val value: Int) {
    TEXT(1),
    VALUE(2),
    PLACEHOLDER(3),
    SOURCE(4),
    WIDTH(5),
    HEIGHT(6),
    FLEX_GROW(7),
    PADDING(8),
    GAP(9),
    BACKGROUND_COLOR(10),
    TEXT_COLOR(11),
    FONT_SIZE(12),
    ENABLED(13),
    ON_PRESS(14),
    ON_CHANGE(15),
    ITEMS(16),
    ACCESSIBILITY_LABEL(17),
    TEST_ID(18),
    ON_LONG_PRESS(19),
    ON_FOCUS(20),
    ON_BLUR(21),
    ON_SUBMIT(22),
    ON_SCROLL(23),
    ON_REFRESH(24),
    ON_TOGGLE(25),
    MARGIN(26),
    MARGIN_HORIZONTAL(27),
    MARGIN_VERTICAL(28),
    PADDING_HORIZONTAL(29),
    PADDING_VERTICAL(30),
    MIN_WIDTH(31),
    MIN_HEIGHT(32),
    MAX_WIDTH(33),
    MAX_HEIGHT(34),
    BORDER_RADIUS(35),
    BORDER_WIDTH(36),
    BORDER_COLOR(37),
    OPACITY(38),
    ALIGN_ITEMS(39),
    ALIGN_SELF(40),
    JUSTIFY_CONTENT(41),
    TEXT_ALIGN(42),
    FONT_WEIGHT(43),
    NUMBER_OF_LINES(44),
    MULTILINE(45),
    SECURE(46),
    KEYBOARD_TYPE(47),
    AUTO_COMPLETE(48),
    INPUT_DEBOUNCE_MS(49),
    INPUT_SYNC_MODE(50),
    CHECKED(51),
    LOADING(52),
    PROGRESS_COLOR(53),
    IMAGE_FIT(54),
    TINT_COLOR(55),
    ELEVATION(56),
    VISIBLE(57),
    MODAL_PRESENTATION(58),
    STATUS_BAR_COLOR(59),
    STATUS_BAR_STYLE(60),
    STATUS_BAR_HIDDEN(61),
    KEYBOARD_BEHAVIOR(62),
    REFRESHING(63),
    SCROLL_ENABLED(64),
    SHOWS_SCROLL_INDICATOR(65),
    SELECTED(66),
    RIPPLE_COLOR(67),
    PRESS_OPACITY(68),
    COLLAPSABLE(69),
    ACCESSIBILITY_ROLE(70),
    ACCESSIBILITY_HINT(71),
    TRANSLATION_X(72),
    TRANSLATION_Y(73),
    SCALE_X(74),
    SCALE_Y(75),
    ROTATION(76),
    ANIMATION_DURATION_MS(77),
    ANIMATION_EASING(78),
    ANIMATE_CHANGES(79),
    SECTION_ITEMS(80),
    LIST_ROW_HEIGHT(81),
    LIST_PREFETCH(82),
    ON_END_REACHED(83),
    END_REACHED_THRESHOLD(84),
    DRAWER_OPEN(85),
    DRAWER_POSITION(86),
    ON_DRAWER_OPEN(87),
    ON_DRAWER_CLOSE(88),
    LETTER_SPACING(89),
    LINE_HEIGHT(90),
    PLACEHOLDER_COLOR(91),
    SELECTION_COLOR(92),
    MAX_LENGTH(93),
    AUTO_FOCUS(94),
    RETURN_KEY_TYPE(95),
    HIT_SLOP(96),
    Z_INDEX(97),
    OVERFLOW(98),
    HOST_NAME(99),
    HOST_PROPERTIES(100),
    ON_NATIVE_EVENT(101),
    FLEX_DIRECTION(102),
    FLEX_SHRINK(103),
    PADDING_LEFT(104),
    PADDING_TOP(105),
    PADDING_RIGHT(106),
    PADDING_BOTTOM(107),
    MARGIN_LEFT(108),
    MARGIN_TOP(109),
    MARGIN_RIGHT(110),
    MARGIN_BOTTOM(111),
    POSITION_TYPE(112),
    LEFT(113),
    TOP(114),
    RIGHT(115),
    BOTTOM(116),
    ASPECT_RATIO(117),
    BORDER_TOP_LEFT_RADIUS(118),
    BORDER_TOP_RIGHT_RADIUS(119),
    BORDER_BOTTOM_RIGHT_RADIUS(120),
    BORDER_BOTTOM_LEFT_RADIUS(121),
    BORDER_LEFT_WIDTH(122),
    BORDER_TOP_WIDTH(123),
    BORDER_RIGHT_WIDTH(124),
    BORDER_BOTTOM_WIDTH(125),
    TEXT_DECORATION(126),
    TEXT_TRANSFORM(127),
    FONT_STYLE(128),
    WIDTH_PERCENT(129),
    HEIGHT_PERCENT(130),
    MAX_WIDTH_PERCENT(131),
    MAX_HEIGHT_PERCENT(132),
    POINTER_EVENTS(133),
    SAFE_AREA_BOTTOM(134),
    BLUR_RADIUS(135),
    FONT_FAMILY(136),
    MARGIN_LEFT_AUTO(137),
    TRANSLATION_X_PERCENT(138),
    ANIMATION_KIND(139),
    ACCESSIBLE(140),
    ACCESSIBILITY_LIVE_REGION(141),
    ACCESSIBILITY_IMPORTANCE(142),
    ACCESSIBILITY_EXPANDED(143),
    ACCESSIBILITY_BUSY(144),
    ACCESSIBILITY_CHECKED_STATE(145),
    ACCESSIBILITY_VALUE_MIN(146),
    ACCESSIBILITY_VALUE_MAX(147),
    ACCESSIBILITY_VALUE_NOW(148),
    ACCESSIBILITY_VALUE_TEXT(149),
    SAFE_AREA_TOP(150),
    SAFE_AREA_RIGHT(151),
    SAFE_AREA_BOTTOM_EDGE(152),
    SAFE_AREA_LEFT(153),
    SAFE_AREA_MODE(154),
    KEYBOARD_VERTICAL_OFFSET(155),
    KEYBOARD_AVOIDING_ENABLED(156),
    REFRESH_COLORS(157),
    REFRESH_PROGRESS_BACKGROUND_COLOR(158),
    REFRESH_PROGRESS_VIEW_OFFSET(159),
    REFRESH_INDICATOR_SIZE(160),
    TEXT_SELECTABLE(161),
    TEXT_ELLIPSIZE_MODE(162),
    TEXT_ALLOW_FONT_SCALING(163),
    TEXT_MAX_FONT_SIZE_MULTIPLIER(164),
    TEXT_ADJUSTS_FONT_SIZE_TO_FIT(165),
    TEXT_MINIMUM_FONT_SCALE(166),
    TEXT_BREAK_STRATEGY(167),
    TEXT_HYPHENATION_FREQUENCY(168),
    TEXT_DATA_DETECTOR_TYPE(169),
    STATUS_BAR_ANIMATED(170),
    STATUS_BAR_TRANSLUCENT(171),
    LIST_HORIZONTAL(172),
    LIST_NUM_COLUMNS(173),
    LIST_INVERTED(174),
    LIST_INITIAL_SCROLL_INDEX(175),
    LIST_REMOVE_CLIPPED_SUBVIEWS(176);

    companion object {
        fun from(value: Int): PropKey =
            entries.firstOrNull { it.value == value }
                ?: throw ProtocolException("Unknown property $value")
    }
}

sealed interface PropValue {
    data class Text(val value: String) : PropValue
    data class Integer(val value: Long) : PropValue
    data class Decimal(val value: Double) : PropValue
    data class Flag(val value: Boolean) : PropValue
    data class Bytes(val value: ByteBuffer) : PropValue
    data class Strings(val value: PackedStringList) : PropValue
    data class Sections(val value: PackedSectionList) : PropValue
    data class Properties(val value: Map<String, WireValue>) : PropValue
}

class PackedStringList private constructor(
    private val bytes: ByteBuffer,
    private val offsets: IntArray,
    private val lengths: IntArray,
) {
    val size: Int
        get() = offsets.size

    operator fun get(index: Int): String {
        require(index in offsets.indices) { "List index is out of range" }
        val item = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        item.position(offsets[index])
        val value = ByteArray(lengths[index])
        item.get(value)
        return value.toString(Charsets.UTF_8)
    }

    companion object {
        fun decode(source: ByteBuffer): PackedStringList {
            val bytes = source.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.remaining() >= Int.SIZE_BYTES) { "List payload is truncated" }
            val count = bytes.int.toLong() and 0xffff_ffffL
            require(count <= 100_000) { "List contains too many items" }
            val offsets = IntArray(count.toInt())
            val lengths = IntArray(count.toInt())
            repeat(count.toInt()) { index ->
                require(bytes.remaining() >= Int.SIZE_BYTES) { "List payload is truncated" }
                val length = bytes.int.toLong() and 0xffff_ffffL
                require(length <= MAX_VALUE_BYTES && bytes.remaining() >= length) {
                    "List item is too large or truncated"
                }
                offsets[index] = bytes.position()
                lengths[index] = length.toInt()
                bytes.position(bytes.position() + length.toInt())
            }
            require(!bytes.hasRemaining()) { "List payload contains trailing bytes" }
            return PackedStringList(source.slice().asReadOnlyBuffer(), offsets, lengths)
        }
    }
}

class PackedSectionList private constructor(
    private val bytes: ByteBuffer,
    private val entries: List<Entry>,
) {
    data class Entry(
        val kind: Int,
        val offset: Int,
        val length: Int,
    )

    val size: Int
        get() = entries.size

    fun isHeader(index: Int): Boolean = entries[index].kind == ENTRY_HEADER

    operator fun get(index: Int): String {
        val entry = entries[index]
        val item = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        item.position(entry.offset)
        val value = ByteArray(entry.length)
        item.get(value)
        return value.toString(Charsets.UTF_8)
    }

    companion object {
        const val ENTRY_HEADER = 1
        const val ENTRY_ITEM = 2

        fun decode(source: ByteBuffer): PackedSectionList {
            val bytes = source.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.remaining() >= Int.SIZE_BYTES) { "Section payload is truncated" }
            val count = bytes.int.toLong() and 0xffff_ffffL
            require(count <= 10_000) { "Section list contains too many sections" }
            val entries = ArrayList<Entry>()
            repeat(count.toInt()) {
                entries += bytes.entry(ENTRY_HEADER)
                require(bytes.remaining() >= Int.SIZE_BYTES) { "Section payload is truncated" }
                val items = bytes.int.toLong() and 0xffff_ffffL
                require(items <= 100_000 && entries.size + items <= 100_000) {
                    "Section list contains too many items"
                }
                repeat(items.toInt()) {
                    entries += bytes.entry(ENTRY_ITEM)
                }
            }
            require(!bytes.hasRemaining()) { "Section payload contains trailing bytes" }
            return PackedSectionList(source.slice().asReadOnlyBuffer(), entries)
        }

        private fun ByteBuffer.entry(kind: Int): Entry {
            require(remaining() >= Int.SIZE_BYTES) { "Section payload is truncated" }
            val length = int.toLong() and 0xffff_ffffL
            require(length <= MAX_VALUE_BYTES && remaining() >= length) {
                "Section value is too large or truncated"
            }
            val entry = Entry(kind, position(), length.toInt())
            position(position() + length.toInt())
            return entry
        }
    }
}

data class NodeSpec(
    val id: Long,
    val parent: Long,
    val index: Int,
    val kind: NodeKind,
    val properties: Map<PropKey, PropValue>,
)

data class Frame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

sealed interface Mutation {
    data class Create(val node: NodeSpec) : Mutation
    data class Remove(val id: Long) : Mutation
    data class Update(
        val id: Long,
        val key: PropKey,
        val value: PropValue?,
    ) : Mutation

    data class Move(
        val id: Long,
        val parent: Long,
        val index: Int,
    ) : Mutation

    data class Layout(
        val id: Long,
        val frame: Frame,
    ) : Mutation

    data class SetRoot(val id: Long) : Mutation
}

object BatchDecoder {
    fun decode(bytes: ByteBuffer): List<Mutation> {
        require(bytes.remaining() <= MAX_FRAME_BYTES) { "Pam Native batch exceeds 16 MiB" }
        val reader = BinaryReader(bytes)
        check(reader.ascii(4) == "PNB1") { "Invalid Pam Native batch magic" }
        check(reader.u16() == PAM_PROTOCOL_VERSION) { "Unsupported Pam Native protocol" }
        val count = reader.u32()
        check(count <= MAX_MUTATIONS) { "Pam Native batch contains too many mutations" }
        val mutations = ArrayList<Mutation>(count)
        repeat(count) {
            mutations += when (val kind = reader.u8()) {
                1 -> Mutation.Create(reader.node())
                2 -> Mutation.Remove(reader.positiveId())
                3 -> {
                    val id = reader.positiveId()
                    val key = PropKey.from(reader.u16())
                    val value = when (val presence = reader.u8()) {
                        1 -> reader.value(key)
                        2 -> null
                        else -> throw ProtocolException("Unknown update value marker $presence")
                    }
                    Mutation.Update(id, key, value)
                }
                4 -> Mutation.Move(
                    id = reader.positiveId(),
                    parent = reader.u64(),
                    index = reader.u32(),
                )
                5 -> Mutation.Layout(
                    id = reader.positiveId(),
                    frame = Frame(
                        x = reader.f32(),
                        y = reader.f32(),
                        width = reader.f32(),
                        height = reader.f32(),
                    ),
                )
                6 -> Mutation.SetRoot(reader.positiveId())
                else -> throw ProtocolException("Unknown mutation kind $kind")
            }
        }
        reader.finish()
        return mutations
    }
}

private class BinaryReader(bytes: ByteBuffer) {
    private val buffer = bytes.slice().order(ByteOrder.LITTLE_ENDIAN)

    fun u8(): Int = take(Byte.SIZE_BYTES).get().toInt() and 0xff

    fun u16(): Int = take(Short.SIZE_BYTES).short.toInt() and 0xffff

    fun u32(): Int {
        val value = take(Int.SIZE_BYTES).int.toLong() and 0xffff_ffffL
        if (value > Int.MAX_VALUE) {
            throw ProtocolException("Protocol count exceeds Android capacity")
        }
        return value.toInt()
    }

    fun u64(): Long = take(Long.SIZE_BYTES).long

    fun positiveId(): Long =
        u64().also { value ->
            if (value <= 0) {
                throw ProtocolException("Node identifiers must be positive")
            }
        }

    fun f32(): Float =
        take(Float.SIZE_BYTES).float.also { value ->
            if (!value.isFinite() || value < 0f) {
                throw ProtocolException("Layout value must be finite and non-negative")
            }
        }

    fun ascii(length: Int): String = bytes(length).toString(Charsets.US_ASCII)

    fun node(): NodeSpec {
        val id = positiveId()
        val parent = u64()
        val index = u32()
        val kind = NodeKind.from(u8())
        val propertyCount = u16()
        check(propertyCount <= MAX_PROPERTIES) { "Node has too many properties" }
        val properties = LinkedHashMap<PropKey, PropValue>(propertyCount)
        repeat(propertyCount) {
            val key = PropKey.from(u16())
            check(properties.put(key, value(key)) == null) {
                "Node contains duplicate property ${key.name}"
            }
        }
        return NodeSpec(id, parent, index, kind, properties)
    }

    fun value(key: PropKey? = null): PropValue =
        when (val tag = u8()) {
            1 -> PropValue.Text(sizedBytes().toString(Charsets.UTF_8))
            2 -> PropValue.Integer(take(Long.SIZE_BYTES).long)
            3 -> PropValue.Decimal(take(Double.SIZE_BYTES).double)
            4 -> when (val value = u8()) {
                0 -> PropValue.Flag(false)
                1 -> PropValue.Flag(true)
                else -> throw ProtocolException("Invalid boolean value $value")
            }
            5 -> {
                val value = sizedBuffer()
                when (key) {
                    PropKey.ITEMS -> PropValue.Strings(PackedStringList.decode(value))
                    PropKey.SECTION_ITEMS -> PropValue.Sections(PackedSectionList.decode(value))
                    PropKey.HOST_PROPERTIES -> PropValue.Properties(WireMap.decode(value))
                    else -> PropValue.Bytes(value)
                }
            }
            else -> throw ProtocolException("Unknown property value tag $tag")
        }

    fun finish() {
        check(!buffer.hasRemaining()) { "Pam Native batch contains trailing bytes" }
    }

    private fun sizedBytes(): ByteArray {
        val length = u32()
        check(length <= MAX_VALUE_BYTES) { "Pam Native property exceeds one MiB" }
        return bytes(length)
    }

    private fun sizedBuffer(): ByteBuffer {
        val length = u32()
        check(length <= MAX_VALUE_BYTES) { "Pam Native property exceeds one MiB" }
        val owned = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        owned.put(take(length))
        owned.flip()
        return owned.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun bytes(length: Int): ByteArray {
        val output = ByteArray(length)
        take(length).get(output)
        return output
    }

    private fun take(length: Int): ByteBuffer {
        if (length < 0 || buffer.remaining() < length) {
            throw ProtocolException("Pam Native batch is truncated")
        }
        val slice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.limit(length)
        buffer.position(buffer.position() + length)
        return slice
    }
}

class ProtocolException(message: String) : IllegalArgumentException(message)
