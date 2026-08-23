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
import PamPlugin10PushinbrPamNativeMediaEditor
import PamPlugin11PushinbrPamNativeNfc
import PamPlugin12PushinbrPamNativePayments
import PamPlugin13PushinbrPamNativeRealtime
import PamPlugin14PushinbrPamNativeScanner
import PamPlugin15PushinbrPamNativeShareExtension
import PamPlugin16PushinbrPamNativeSubscriptions
import PamPlugin17PushinbrPamNativeVideo
import PamPlugin18PushinbrPamNativeWidgets
import PamPlugin19PushinbrPamNativeCamera
import PamPlugin20PushinbrPamNativeCanvas
import PamPlugin21PushinbrPamNativeGpu
import PamPlugin22PushinbrPamNative3d

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
            "media-editor": MediaEditorModule(),
            "nfc": NfcModule(),
            "realtime": RealtimeModule(),
            "share-extension": ShareExtensionModule(),
            "subscriptions": SubscriptionsModule(),
            "widgets": WidgetsModule(),
            "camera": CameraModule(),
        ]
    }

    public static func views() -> [String: NativeViewFactory] {
        [
            "maps.map": MapViewFactory(),
            "payments.sheet": PamPaymentSheetFactory(),
            "scanner.camera": ScannerViewFactory(),
            "video.player": VideoPlayerFactory(),
            "camera.preview": PamPlugin19PushinbrPamNativeCamera.CameraViewFactory(),
            "canvas.view": PamPlugin20PushinbrPamNativeCanvas.CanvasViewFactory(),
            "gpu.surface": PamPlugin21PushinbrPamNativeGpu.GpuViewFactory(),
            "three-d.scene": PamPlugin22PushinbrPamNative3d.SceneViewFactory(),
        ]
    }
}
