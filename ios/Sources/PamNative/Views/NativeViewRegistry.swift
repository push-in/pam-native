import Foundation
import UIKit

public final class NativeViewRegistry {
    private let factories: [String: NativeViewFactory]
    private var owners: [ObjectIdentifier: NativeViewFactory] = [:]

    public init() {
        self.factories = GeneratedPamViews.create()
    }

    public func create(name: String, emit: @escaping (Int, Data) -> Void) -> UIView {
        guard let factory = factories[name] else {
            fatalError("Unknown generated native view \(name)")
        }

        let view = factory.create(context: nil) { payload in
            emit(NativeViewEventKind.native.rawValue, payload)
        }
        owners[ObjectIdentifier(view)] = factory
        return view
    }

    public func update(view: UIView, properties: [String: WireValue]) {
        guard let factory = owners[ObjectIdentifier(view)] else {
            fatalError("Native view has no registered factory")
        }
        factory.update(view: view, properties: properties)
    }

    public func release(view: UIView) {
        guard let factory = owners.removeValue(forKey: ObjectIdentifier(view)) else {
            return
        }
        factory.release(view: view)
    }

    public func close() {
        for (id, factory) in owners {
            _ = id
            factory.close()
        }
        owners.removeAll()
    }
}
