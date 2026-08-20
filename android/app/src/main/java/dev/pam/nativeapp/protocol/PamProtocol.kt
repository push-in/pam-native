package dev.pam.nativeapp.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val PAM_PROTOCOL_VERSION = 1
private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
private const val MAX_MUTATIONS = 800_000
private const val MAX_PROPERTIES = 128
private const val MAX_VALUE_BYTES = 1024 * 1024

private fun strictUtf8(value: ByteBuffer, label: String): String =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(value)
            .toString()
    } catch (error: CharacterCodingException) {
        throw ProtocolException("$label is not valid UTF-8")
    }

private fun strictUtf8(value: ByteArray, label: String): String =
    strictUtf8(ByteBuffer.wrap(value), label)

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
    CUSTOM_VIEW(24),
    NAVIGATION_HOST(25),
    VIRTUAL_LIST(26),
    WEB_VIEW(27),
    MEDIA(28),
    DRAWING_CANVAS(29),
    TAB_HOST(30),
    CANVAS(31);

    companion object {
        fun from(value: Int): NodeKind =
            entries.firstOrNull { it.value == value }
                ?: throw ProtocolException("Unknown node kind $value")
    }
}

enum class EventKind(val value: Int) {
    PRESS(1),
    CHANGE(2),
    BACK(3),
    MODULE_RESULT(4),
    LONG_PRESS(5),
    FOCUS(6),
    BLUR(7),
    SUBMIT(8),
    SCROLL(9),
    REFRESH(10),
    TOGGLE(11),
    END_REACHED(12),
    DRAWER_OPEN(13),
    DRAWER_CLOSE(14),
    NATIVE(15),
    APP_STATE(16),
    DIMENSIONS(17),
    MEMORY_PRESSURE(18),
    IMAGE_LOAD_START(19),
    IMAGE_PROGRESS(20),
    IMAGE_LOAD(21),
    IMAGE_ERROR(22),
    IMAGE_LOAD_END(23),
    INPUT_END_EDITING(24),
    INPUT_SELECTION_CHANGE(25),
    INPUT_CONTENT_SIZE_CHANGE(26),
    INPUT_KEY_PRESS(27),
    PRESS_IN(28),
    PRESS_OUT(29),
    PRESS_MOVE(30),
    MODAL_REQUEST_CLOSE(31),
    MODAL_SHOW(32),
    MODAL_DISMISS(33),
    MODAL_ORIENTATION_CHANGE(34),
    CLICK_OUTSIDE(35),
    INTERSECT(36),
    MUTATE(37),
    RESIZE(38),
    TOUCH_START(39),
    TOUCH_MOVE(40),
    TOUCH_END(41),
    GESTURE_BEGIN(42),
    GESTURE_UPDATE(43),
    GESTURE_END(44),
    GESTURE_CANCEL(45),
    BOTTOM_SHEET_CHANGE(46),
    BOTTOM_SHEET_DISMISS(47),
    WEB_VIEW_LOAD(48),
    WEB_VIEW_ERROR(49),
    WEB_VIEW_MESSAGE(50),
    MEDIA_READY(51),
    MEDIA_PROGRESS(52),
    MEDIA_END(53),
    MEDIA_ERROR(54),
    DRAG_START(55),
    DRAG_END(56),
    DROP(57),
    MENU_ACTION(58),
    NAVIGATION_GESTURE_POP(59),
    ANIMATION_COMPLETE(60),
    MEDIA_CACHE_HIT(61),
    MEDIA_CACHE_MISS(62),
    MEDIA_CACHE_PROGRESS(63),
    MEDIA_CACHE_READY(64),
    ACCESSIBILITY_ACTION(65);
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
    LIST_REMOVE_CLIPPED_SUBVIEWS(176),
    SCROLL_HORIZONTAL(177),
    SCROLL_CONTENT_OFFSET_X(178),
    SCROLL_CONTENT_OFFSET_Y(179),
    SCROLL_FILL_VIEWPORT(180),
    SCROLL_OVER_SCROLL_MODE(181),
    SCROLL_NESTED_ENABLED(182),
    SCROLL_FADING_EDGE_LENGTH(183),
    SCROLL_PERSISTENT_SCROLLBAR(184),
    SCROLL_PAGING_ENABLED(185),
    SCROLL_SNAP_INTERVAL(186),
    SCROLL_DECELERATION_RATE(187),
    SCROLL_KEYBOARD_DISMISS_MODE(188),
    ACTIVITY_ANIMATING(189),
    ACTIVITY_HIDES_WHEN_STOPPED(190),
    ACTIVITY_SIZE(191),
    SWITCH_TRACK_COLOR_FALSE(192),
    SWITCH_TRACK_COLOR_TRUE(193),
    SWITCH_THUMB_COLOR(194),
    IMAGE_DEFAULT_SOURCE(195),
    IMAGE_LOADING_INDICATOR_SOURCE(196),
    IMAGE_FADE_DURATION_MS(197),
    IMAGE_RESIZE_METHOD(198),
    IMAGE_RESIZE_MULTIPLIER(199),
    IMAGE_PROGRESSIVE_RENDERING_ENABLED(200),
    IMAGE_CACHE_POLICY(201),
    IMAGE_OVERLAY_COLOR(202),
    IMAGE_SOURCE_SET(203),
    IMAGE_REQUEST_HEADERS(204),
    ON_IMAGE_LOAD_START(205),
    ON_IMAGE_PROGRESS(206),
    ON_IMAGE_LOAD(207),
    ON_IMAGE_ERROR(208),
    ON_IMAGE_LOAD_END(209),
    INPUT_EDITABLE(210),
    INPUT_AUTO_CORRECT(211),
    INPUT_AUTO_CAPITALIZE(212),
    INPUT_CARET_HIDDEN(213),
    INPUT_CONTEXT_MENU_HIDDEN(214),
    INPUT_CURSOR_COLOR(215),
    INPUT_DISABLE_FULLSCREEN_UI(216),
    INPUT_AUTOFILL_IMPORTANCE(217),
    INPUT_MODE(218),
    INPUT_MIN_LINES(219),
    INPUT_SELECT_TEXT_ON_FOCUS(220),
    INPUT_SELECTION_START(221),
    INPUT_SELECTION_END(222),
    INPUT_SHOW_SOFT_INPUT_ON_FOCUS(223),
    INPUT_SUBMIT_BEHAVIOR(224),
    INPUT_TEXT_ALIGN_VERTICAL(225),
    INPUT_RETURN_KEY_LABEL(226),
    INPUT_SCROLL_ENABLED(227),
    INPUT_UNDERLINE_COLOR(228),
    ON_INPUT_END_EDITING(229),
    ON_INPUT_SELECTION_CHANGE(230),
    ON_INPUT_CONTENT_SIZE_CHANGE(231),
    ON_INPUT_KEY_PRESS(232),
    HIT_SLOP_LEFT(233),
    HIT_SLOP_TOP(234),
    HIT_SLOP_RIGHT(235),
    HIT_SLOP_BOTTOM(236),
    PRESS_RETENTION_LEFT(237),
    PRESS_RETENTION_TOP(238),
    PRESS_RETENTION_RIGHT(239),
    PRESS_RETENTION_BOTTOM(240),
    PRESS_DELAY_LONG_MS(241),
    PRESS_DELAY_IN_MS(242),
    PRESS_DELAY_OUT_MS(243),
    PRESS_ANDROID_DISABLE_SOUND(244),
    RIPPLE_BORDERLESS(245),
    RIPPLE_RADIUS(246),
    RIPPLE_FOREGROUND(247),
    RIPPLE_ALPHA(248),
    ON_PRESS_IN(249),
    ON_PRESS_OUT(250),
    ON_PRESS_MOVE(251),
    MODAL_ANIMATION_TYPE(252),
    MODAL_BACKDROP_COLOR(253),
    MODAL_TRANSPARENT(254),
    MODAL_HARDWARE_ACCELERATED(255),
    MODAL_NAVIGATION_BAR_TRANSLUCENT(256),
    MODAL_STATUS_BAR_TRANSLUCENT(257),
    MODAL_ALLOW_SWIPE_DISMISSAL(258),
    ON_MODAL_REQUEST_CLOSE(259),
    ON_MODAL_SHOW(260),
    ON_MODAL_DISMISS(261),
    ON_MODAL_ORIENTATION_CHANGE(262),
    GRID_COLUMNS(263),
    GRID_SPAN(264),
    GRID_SPAN_SM(265),
    GRID_SPAN_MD(266),
    GRID_SPAN_LG(267),
    GRID_SPAN_XL(268),
    GRID_OFFSET(269),
    GRID_OFFSET_SM(270),
    GRID_OFFSET_MD(271),
    GRID_OFFSET_LG(272),
    GRID_OFFSET_XL(273),
    GRID_ORDER(274),
    GRID_ORDER_SM(275),
    GRID_ORDER_MD(276),
    GRID_ORDER_LG(277),
    GRID_ORDER_XL(278),
    GRID_COLUMN_GAP(279),
    GRID_ROW_GAP(280),
    NAVIGATION_OPERATION(281),
    NAVIGATION_TRANSITION(282),
    NAVIGATION_DURATION_MS(283),
    NAVIGATION_REVISION(284),
    ON_CLICK_OUTSIDE(285),
    ON_INTERSECT(286),
    ON_MUTATE(287),
    ON_RESIZE(288),
    ON_TOUCH_START(289),
    ON_TOUCH_MOVE(290),
    ON_TOUCH_END(291),
    DRAWER_TYPE(292),
    DRAWER_WIDTH(293),
    DRAWER_OVERLAY_COLOR(294),
    DRAWER_SWIPE_ENABLED(295),
    DRAWER_SWIPE_EDGE_WIDTH(296),
    DRAWER_SWIPE_MIN_DISTANCE(297),
    DRAWER_KEYBOARD_DISMISS_MODE(298),
    DRAWER_HIDE_STATUS_BAR_ON_OPEN(299),
    DRAWER_STATUS_BAR_ANIMATION(300),
    DRAWER_PERMANENT_BREAKPOINT(301),
    LAYOUT_DIRECTION(302),
    GESTURE_TYPE(303),
    GESTURE_ENABLED(304),
    GESTURE_MIN_POINTERS(305),
    GESTURE_MAX_POINTERS(306),
    GESTURE_DIRECTION(307),
    GESTURE_COMPOSITION(308),
    GESTURE_MIN_DISTANCE(309),
    GESTURE_MIN_DURATION_MS(310),
    ON_GESTURE_BEGIN(311),
    ON_GESTURE_UPDATE(312),
    ON_GESTURE_END(313),
    ON_GESTURE_CANCEL(314),
    BOTTOM_SHEET_SNAP_POINTS(315),
    BOTTOM_SHEET_INDEX(316),
    BOTTOM_SHEET_DISMISSIBLE(317),
    BOTTOM_SHEET_BACKDROP_DISMISS(318),
    BOTTOM_SHEET_HANDLE_VISIBLE(319),
    BOTTOM_SHEET_DRAG_ENABLED(320),
    BOTTOM_SHEET_KEYBOARD_BEHAVIOR(321),
    BOTTOM_SHEET_CORNER_RADIUS(322),
    ON_BOTTOM_SHEET_CHANGE(323),
    ON_BOTTOM_SHEET_DISMISS(324),
    WEB_VIEW_SOURCE(325),
    WEB_VIEW_JAVA_SCRIPT_ENABLED(326),
    WEB_VIEW_DOM_STORAGE_ENABLED(327),
    WEB_VIEW_USER_AGENT(328),
    WEB_VIEW_INJECTED_JAVA_SCRIPT(329),
    WEB_VIEW_ALLOWS_INLINE_MEDIA(330),
    ON_WEB_VIEW_LOAD(331),
    ON_WEB_VIEW_ERROR(332),
    ON_WEB_VIEW_MESSAGE(333),
    MEDIA_SOURCE(334),
    MEDIA_TYPE(335),
    MEDIA_AUTO_PLAY(336),
    MEDIA_CONTROLS(337),
    MEDIA_LOOP(338),
    MEDIA_MUTED(339),
    MEDIA_VOLUME(340),
    MEDIA_CURRENT_TIME(341),
    MEDIA_PLAYBACK_RATE(342),
    ON_MEDIA_READY(343),
    ON_MEDIA_PROGRESS(344),
    ON_MEDIA_END(345),
    ON_MEDIA_ERROR(346),
    DRAGGABLE(347),
    DRAG_DATA(348),
    DROP_ENABLED(349),
    CONTEXT_MENU_ITEMS(350),
    ON_DRAG_START(351),
    ON_DRAG_END(352),
    ON_DROP(353),
    ON_MENU_ACTION(354),
    NAVIGATION_GESTURE_ENABLED(355),
    NAVIGATION_GESTURE_EDGE_WIDTH(356),
    NAVIGATION_GESTURE_THRESHOLD(357),
    ON_NAVIGATION_GESTURE_POP(358),
    ANIMATION_KEYFRAMES(359),
    ANIMATION_ITERATIONS(360),
    ANIMATION_DELAY_MS(361),
    ANIMATION_FILL_MODE(362),
    ANIMATION_PLAY_STATE(363),
    ANIMATION_AUTO_REVERSE(364),
    ON_ANIMATION_COMPLETE(365),
    WEB_VIEW_ALLOWED_HOSTS(366),
    MEDIA_CACHE_POLICY(367),
    MEDIA_CACHE_KEY(368),
    MEDIA_CACHE_MAX_AGE_MS(369),
    MEDIA_CACHE_TAGS(370),
    MEDIA_CACHE_PIN_OFFLINE(371),
    MEDIA_CACHE_STREAMING(372),
    MEDIA_CACHE_PRELOAD_SECONDS(373),
    MEDIA_CACHE_DOWNLOAD_WHILE_PLAYING(374),
    MEDIA_CACHE_MAX_BYTES(375),
    MEDIA_THUMBNAIL_SOURCE(376),
    MEDIA_RESIZE_WIDTH(377),
    MEDIA_RESIZE_HEIGHT(378),
    MEDIA_PRIORITY(379),
    ON_MEDIA_CACHE_HIT(380),
    ON_MEDIA_CACHE_MISS(381),
    ON_MEDIA_CACHE_PROGRESS(382),
    ON_MEDIA_CACHE_READY(383),
    MEDIA_CACHE_CHECKSUM(384),
    SCROLL_ANCHOR_TO_END(385),
    SCROLL_MAINTAIN_VISIBLE_CONTENT_POSITION(386),
    SCROLL_AUTO_SCROLL_TO_END_THRESHOLD(387),
    SCROLL_TARGET_TEST_ID(388),
    SCROLL_REQUEST(389),
    SCROLL_TARGET_OFFSET(390),
    DRAWING_COLOR(391),
    DRAWING_WIDTH(392),
    DRAWING_MODE(393),
    DRAWING_CLEAR_REQUEST(394),
    DRAWING_UNDO_REQUEST(395),
    FLEX_WRAP(396),
    LEFT_PERCENT(397),
    TOP_PERCENT(398),
    RIGHT_PERCENT(399),
    BOTTOM_PERCENT(400),
    SHADOW_OFFSET_X(401),
    SHADOW_OFFSET_Y(402),
    SHADOW_BLUR_RADIUS(403),
    SHADOW_SPREAD_RADIUS(404),
    SHADOW_COLOR(405),
    GESTURE_NATIVE_TRANSFORM(406),
    GESTURE_NATIVE_MIN_SCALE(407),
    GESTURE_NATIVE_MAX_SCALE(408),
    GESTURE_NATIVE_RESET_KEY(409),
    NAVIGATION_ORIENTATION(410),
    NAVIGATION_AUTO_HIDE_HOME_INDICATOR(411),
    SHARED_TRANSITION_TAG(412),
    NAVIGATION_TITLE(413),
    NAVIGATION_HEADER_SHOWN(414),
    NAVIGATION_HEADER_TRANSPARENT(415),
    NAVIGATION_HEADER_BACKGROUND_COLOR(416),
    NAVIGATION_HEADER_TINT_COLOR(417),
    NAVIGATION_HEADER_SHADOW_VISIBLE(418),
    NAVIGATION_HEADER_LARGE_TITLE_ENABLED(419),
    NAVIGATION_HEADER_SEARCH_ENABLED(420),
    NAVIGATION_HEADER_SEARCH_PLACEHOLDER(421),
    NAVIGATION_PRESENTATION(422),
    NAVIGATION_GESTURE_DIRECTION(423),
    NAVIGATION_FULL_SCREEN_GESTURE_ENABLED(424),
    NAVIGATION_FREEZE_ON_BLUR(425),
    NAVIGATION_SHEET_DETENTS(426),
    NAVIGATION_SHEET_INITIAL_DETENT_INDEX(427),
    NAVIGATION_SHEET_GRABBER_VISIBLE(428),
    NAVIGATION_SHEET_CORNER_RADIUS(429),
    NAVIGATION_SHEET_EXPANDS_WHEN_SCROLLED_TO_EDGE(430),
    TAB_ITEMS(431),
    TAB_SELECTED_INDEX(432),
    TAB_POSITION(433),
    TAB_ACTIVE_COLOR(434),
    TAB_INACTIVE_COLOR(435),
    TAB_BACKGROUND_COLOR(436),
    TAB_INDICATOR_COLOR(437),
    TAB_SWIPE_ENABLED(438),
    TAB_SCROLL_ENABLED(439),
    CANVAS_COMMANDS(440),
    WORKLET_PROGRAM(441),
    WORKLET_TARGET(442),
    WORKLET_DURATION_MS(443),
    WORKLET_ITERATIONS(444),
    NAVIGATION_BAR_HIDDEN(445),
    BORDER_STYLE(446),
    SCROLL_TARGET_ALIGNMENT(447),
    PRESS_SCALE(448),
    SHARED_TRANSITION_CONFIG(449),
    ACCESSIBILITY_ACTIONS(450),
    ON_ACCESSIBILITY_ACTION(451);

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
        return strictUtf8(value, "List item")
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
                strictUtf8(bytes.slice().apply { limit(length.toInt()) }, "List item")
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
        return strictUtf8(value, "Section value")
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
            strictUtf8(slice().apply { limit(length.toInt()) }, "Section value")
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
            1 -> PropValue.Text(strictUtf8(sizedBytes(), "Text property"))
            2 -> PropValue.Integer(take(Long.SIZE_BYTES).long)
            3 -> PropValue.Decimal(
                take(Double.SIZE_BYTES).double.also { value ->
                    if (!value.isFinite()) {
                        throw ProtocolException("Floating property must be finite")
                    }
                },
            )
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
