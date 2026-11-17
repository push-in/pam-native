package dev.pam.nativeapp.render

import android.graphics.Bitmap
import android.os.Build

/**
 * `Bitmap.Config.HARDWARE` is API 26+: on Android 7.x even reading the enum
 * constant throws NoSuchFieldError, so every comparison goes through here.
 */
internal val Bitmap.isHardware: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE

/** The GPU config where it exists; heap `ARGB_8888` below API 26. */
internal val hardwareBitmapConfig: Bitmap.Config
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888
