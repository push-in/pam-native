package dev.pam.nativeapp.push

import android.content.Context
import android.content.Intent

/**
 * Stable Android contract for PAM Native plugins that must react to data-only
 * push messages while the PHP runtime is suspended.
 *
 * Plugin receivers must be declared with `android:exported="false"`. PAM sends
 * the broadcast only to the current application package.
 */
public object BackgroundPush {
    public const val ACTION_RECEIVED: String = "dev.pam.nativeapp.action.PUSH_RECEIVED"
    public const val EXTRA_BODY: String = "dev.pam.nativeapp.push.BODY"
    public const val EXTRA_DATA_JSON: String = "dev.pam.nativeapp.push.DATA_JSON"
    public const val EXTRA_DEEP_LINK: String = "dev.pam.nativeapp.push.DEEP_LINK"
    public const val EXTRA_ID: String = "dev.pam.nativeapp.push.ID"
    public const val EXTRA_TITLE: String = "dev.pam.nativeapp.push.TITLE"

    @JvmStatic
    public fun receivedIntent(
        context: Context,
        id: String,
        title: String = "",
        body: String = "",
        dataJson: String = "{}",
        deepLink: String = "",
    ): Intent = Intent(ACTION_RECEIVED)
        .setPackage(context.packageName)
        .putExtra(EXTRA_ID, id.take(512))
        .putExtra(EXTRA_TITLE, title.take(4_096))
        .putExtra(EXTRA_BODY, body.take(16_384))
        .putExtra(EXTRA_DATA_JSON, dataJson.take(MAX_DATA_BYTES))
        .putExtra(EXTRA_DEEP_LINK, deepLink.take(8_192))

    private const val MAX_DATA_BYTES: Int = 256 * 1024
}
