package dev.pam.nativeapp.views

enum class NativeViewEventKind(val value: Int) {
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
}
