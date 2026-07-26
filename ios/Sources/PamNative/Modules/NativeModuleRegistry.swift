import Foundation

public final class NativeModuleRegistry: @unchecked Sendable {
    private let http = HttpModule()
    private let storage = StorageModule()
    private let system = SystemModule()
    private let modules: [String: NativeModule]

    public init() {
        var values: [String: NativeModule] = [
            "http": http,
            "storage": storage,
        ]
        GeneratedPamModules.create().forEach { values[$0.key] = $0.value }
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
