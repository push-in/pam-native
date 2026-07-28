import Foundation

public enum ModuleResultStatus: Int {
    case success = 1
    case failure = 2
}

public typealias ModuleCompletion = (ModuleResultStatus, Data) -> Void

public protocol NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion)
}

public protocol ClosableNativeModule {
    func close()
}

public enum NativeOperation: Int {
    case httpGet = 1
    case storageGet = 2
    case storageSet = 3
    case alert = 4
    case toast = 5
    case share = 6
    case openUrl = 7
    case canOpenUrl = 8
    case vibrate = 9
    case deviceInfo = 10
    case keyboardDismiss = 11
    case permissionCheck = 12
    case permissionRequest = 13
    case closeApp = 14
    case haptic = 15
    case clipboardSetText = 16
    case clipboardGetText = 17
    case clipboardHasText = 18
    case sensorRead = 19
}
