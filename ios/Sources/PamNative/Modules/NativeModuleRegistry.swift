import Foundation

public final class NativeModuleRegistry: @unchecked Sendable {
    private let http = HttpModule()
    private let storage = StorageModule()
    private let system = SystemModule()
    private let sqlite = SQLiteModule()
    private let files = FilesModule()
    private let notifications = NotificationsModule()
    private let linking = LinkingModule()
    private let cache = CacheModule()
    private let background = BackgroundModule()
    private let device = DeviceModule()
    private let permissions = PermissionsModule()
    private let sensors = SensorsModule()
    private let contacts = ContactsModule()
    private let sms = SmsModule()
    private let location = LocationModule()
    private let audioRecorder = AudioRecorderModule()
    private let imageEditor = ImageEditorModule()
    private let timers = TimersModule()
    private let modules: [String: NativeModule]

    public init(additionalModules: [String: NativeModule] = [:]) {
        var values: [String: NativeModule] = [
            "http": http,
            "storage": storage,
            "sqlite": sqlite,
            "files": files,
            "notifications": notifications,
            "linking": linking,
            "cache": cache,
            "background": background,
            "device": device,
            "permissions": permissions,
            "sensors": sensors,
            "contacts": contacts,
            "sms": sms,
            "location": location,
            "audio-recorder": audioRecorder,
            "image-editor": imageEditor,
            "timers": timers,
        ]
        GeneratedPamModules.create().forEach { values[$0.key] = $0.value }
        additionalModules.forEach { name, module in
            precondition(values[name] == nil, "Duplicate native module \(name)")
            values[name] = module
        }
        self.modules = values
    }

    public func invoke(
        operationValue: Int,
        payload: Data,
        completion: @escaping ModuleCompletion
    ) {
        switch NativeOperation(rawValue: operationValue) {
        case .httpGet:
            http.invoke(method: "get", payload: payload, completion: completion)
        case .storageGet:
            storage.invoke(method: "get", payload: payload, completion: completion)
        case .storageSet:
            storage.invoke(method: "set", payload: payload, completion: completion)
        case .alert,
             .toast,
             .share,
             .openUrl,
             .canOpenUrl,
             .vibrate,
             .deviceInfo,
             .keyboardDismiss,
             .permissionCheck,
             .permissionRequest,
             .haptic,
             .clipboardSetText,
             .clipboardGetText,
             .clipboardHasText,
             .sensorRead,
             .closeApp:
            system.invoke(operation: NativeOperation(rawValue: operationValue)!, payload: payload, completion: completion)
        case .none:
            let message = "Unknown native operation \(operationValue)"
            completion(.failure, message.data(using: .utf8) ?? Data())
        }
    }

    public func invoke(
        module: String,
        method: String,
        payload: Data,
        completion: @escaping ModuleCompletion
    ) {
        guard let implementation = modules[module] else {
            let message = "Unknown native module \(module)"
            completion(.failure, message.data(using: .utf8) ?? Data())
            return
        }
        implementation.invoke(method: method, payload: payload, completion: completion)
    }

    public func close() {
        if let closable = http as? any ClosableNativeModule {
            closable.close()
        }
        if let closable = storage as? any ClosableNativeModule {
            closable.close()
        }
        if let closable = system as? any ClosableNativeModule {
            closable.close()
        }
        modules.values.forEach { module in
            (module as? any ClosableNativeModule)?.close()
        }
    }
}
