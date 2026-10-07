# Migrating to Pam Native 1.26.0

## Images at display size and hardware bitmaps

Rebuild the Android and iOS hosts after updating; no application change is
needed.

- Android and iOS decode an `Image` larger than its view to the smallest size
  that still covers the view. To keep the source resolution (for example a
  photo the user zooms with a native transform), set `resizeMethod="scale"`
  or a `resizeMultiplier` above 1.
- Android API 28+ keeps decoded photos in GPU memory (`HARDWARE` bitmaps).
  Plugins that read an image view's pixels must copy the bitmap first
  (`bitmap.copy(Bitmap.Config.ARGB_8888, false)`); drawing a PAM image view
  into a software `Canvas` is handled by PAM.
- Android releases the pixels of images hidden by an ancestor or detached
  from the window and restores them when shown; `on:load` is not dispatched
  again for a restore.
- Android's decoded-image cache budget follows the device heap class
  (8-64 MiB); iOS image caches follow physical memory.
