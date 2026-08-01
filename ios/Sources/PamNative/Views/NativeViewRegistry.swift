import Foundation
import UIKit

private final class ClosureNativeViewEmitter: NativeViewEmitter {
    private let callback: (Int, Data) -> Void

    init(_ callback: @escaping (Int, Data) -> Void) {
        self.callback = callback
    }

    func emit(kind: NativeViewEventKind, payload: Data) {
        callback(kind.rawValue, payload)
    }
}

public final class NativeViewRegistry {
    private let factories: [String: NativeViewFactory]
    private var owners: [ObjectIdentifier: NativeViewFactory] = [:]

    public init(additionalFactories: [String: NativeViewFactory] = [:]) {
        var values = GeneratedPamViews.create()
        additionalFactories.forEach { name, factory in
            precondition(values[name] == nil, "Duplicate native view \(name)")
            values[name] = factory
        }
        self.factories = values
    }

    public func create(name: String, emit: @escaping (Int, Data) -> Void) -> UIView {
        guard let factory = factories[name] else {
            fatalError("Unknown generated native view \(name)")
        }

        let view = factory.create(
            context: nil,
            emitter: ClosureNativeViewEmitter(emit)
        )
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
