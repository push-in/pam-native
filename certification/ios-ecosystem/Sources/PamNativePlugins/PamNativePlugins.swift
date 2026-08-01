import PamNative
import PamPlugin0PushinbrPamNativeAuth
import PamPlugin1PushinbrPamNativeBackgroundTransfer
import PamPlugin2PushinbrPamNativeBluetooth
import PamPlugin3PushinbrPamNativeFeatureFlags
import PamPlugin4PushinbrPamNativeFirebase
import PamPlugin5PushinbrPamNativeHealth
import PamPlugin6PushinbrPamNativeIntents
import PamPlugin7PushinbrPamNativeLiveActivities
import PamPlugin8PushinbrPamNativeMaps
import PamPlugin9PushinbrPamNativeMedia
import PamPlugin10PushinbrPamNativeNfc
import PamPlugin11PushinbrPamNativePayments
import PamPlugin12PushinbrPamNativeRealtime
import PamPlugin13PushinbrPamNativeScanner
import PamPlugin14PushinbrPamNativeShareExtension
import PamPlugin15PushinbrPamNativeSubscriptions
import PamPlugin16PushinbrPamNativeVideo
import PamPlugin17PushinbrPamNativeWidgets

public enum PamNativePluginRegistry {
    public static func modules() -> [String: NativeModule] {
        [
            "auth.vault": AuthVaultModule(),
            "background-transfer": BackgroundTransferModule(),
            "bluetooth": BluetoothModule(),
            "feature-flags.snapshot": FeatureFlagSnapshotModule(),
            "firebase": FirebaseModule(),
            "health": HealthModule(),
            "intents": IntentsModule(),
            "live-activities": LiveActivitiesModule(),
            "media": MediaModule(),
            "nfc": NfcModule(),
            "realtime": RealtimeModule(),
            "share-extension": ShareExtensionModule(),
            "subscriptions": SubscriptionsModule(),
            "widgets": WidgetsModule(),
        ]
    }

    public static func views() -> [String: NativeViewFactory] {
        [
            "maps.map": MapViewFactory(),
            "payments.sheet": PamPaymentSheetFactory(),
            "scanner.camera": ScannerViewFactory(),
            "video.player": VideoPlayerFactory(),
        ]
    }
}
