import Foundation
import UIKit

public enum NativeViewEventKind: Int {
    case press = 1
    case change = 2
    case back = 3
    case moduleResult = 4
    case longPress = 5
    case focus = 6
    case blur = 7
    case submit = 8
    case scroll = 9
    case refresh = 10
    case toggle = 11
    case endReached = 12
    case drawerOpen = 13
    case drawerClose = 14
    case native = 15
    case appState = 16
    case dimensions = 17
    case memoryPressure = 18
}

public protocol NativeViewEmitter {
    func emit(kind: NativeViewEventKind, payload: Data)
}

public protocol NativeViewFactory {
    func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView
    func create(context: AnyObject?, emitter: NativeViewEmitter) -> UIView
    func update(view: UIView, properties: [String: WireValue])
    func release(view: UIView)
    func close()
}

public extension NativeViewFactory {
    func create(context: AnyObject?, emitter: NativeViewEmitter) -> UIView {
        create(context: context) { payload in
            emitter.emit(kind: .native, payload: payload)
        }
    }

    func release(view: UIView) {}
    func close() {}
}
