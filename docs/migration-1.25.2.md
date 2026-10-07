# Migrating to Pam Native 1.25.2

## Gallery thumbnails

Rebuild the Android and iOS hosts after updating; no application change is
needed.

- Android: an `Image` whose source is a MediaStore `content://media/...` URI
  (images, video or the Files collection) and whose view is at most 640 px
  on its longer side is drawn from the platform thumbnail at the view size.
  Video items now show a frame. `on:load` still reports the item's natural
  width and height. To force a full decode of such a source, use
  `resizeMethod="scale"` or a larger view.
- iOS: `ph://` sources of video assets render the asset's poster frame.
