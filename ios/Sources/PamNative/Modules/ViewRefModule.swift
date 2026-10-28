import Foundation
import UIKit

/// `ViewRef`: imperative operations on a mounted view found by its
/// `nativeRef` (React Native refs): `focus`, `blur`, `clear`, `setSelection`,
/// `isFocused` on text inputs and `measure` on any view. Each call runs once
/// on the main thread; nothing is observed afterwards.
final class ViewRefModule: NativeModule {
    private let findView: (String) -> UIView?

    init(findView: @escaping (String) -> UIView? = MountedViews.mountedView(_:)) {
        self.findView = findView
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard let values = try? WireMap.decode(payload) else {
            completion(.failure, Data("Invalid view ref request".utf8))
            return
        }
        guard case let .text(ref)? = values["ref"], Self.isValidRef(ref) else {
            completion(.failure, Data("A valid nativeRef is required.".utf8))
            return
        }
        var within = ""
        if case let .text(value)? = values["within"] {
            guard Self.isValidRef(value) else {
                completion(.failure, Data("A valid nativeRef is required.".utf8))
                return
            }
            within = value
        }
        let start = Self.integer(values["start"])
        let end = Self.integer(values["end"])
        DispatchQueue.main.async {
            do {
                completion(.success, try self.run(method: method, ref: ref, start: start, end: end, within: within))
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    func run(method: String, ref: String, start: Int = 0, end: Int = 0, within: String = "") throws -> Data {
        dispatchPrecondition(condition: .onQueue(.main))
        // `within`: the first `ref` under the ancestor named `within` (a ref
        // repeated by every row of a list).
        let target = within.isEmpty ? findView(ref) : findView(within)?.pamFindNativeRef(ref)
        guard let view = target else {
            throw ViewRefError("No mounted view has nativeRef \(within.isEmpty ? ref : "\(ref) within \(within)").")
        }
        switch method {
        case "measure":
            return try WireMap.encode(try Self.frame(of: view, ref: ref).mapValues { .decimal($0) })
        case "focus":
            let input = try Self.input(in: view, ref: ref)
            guard input.window != nil, !input.isHidden else { throw ViewRefError("The input \(ref) is not visible.") }
            if !input.isFirstResponder { _ = input.becomeFirstResponder() }
        case "blur":
            let input = try Self.input(in: view, ref: ref)
            if input.isFirstResponder { _ = input.resignFirstResponder() }
        case "clear":
            let input = try Self.input(in: view, ref: ref)
            // Like user editing: the input's on:change reports "" so a
            // controlled value follows.
            if let field = input as? UITextField {
                field.text = ""
                field.sendActions(for: .editingChanged)
            } else if let area = input as? UITextView {
                area.text = ""
                area.delegate?.textViewDidChange?(area)
            }
        case "setSelection":
            let input = try Self.input(in: view, ref: ref)
            guard let text = input as? UITextInput else { break }
            let length = text.offset(from: text.beginningOfDocument, to: text.endOfDocument)
            let from = max(0, min(start, length))
            let to = max(from, min(end, length))
            if let a = text.position(from: text.beginningOfDocument, offset: from),
               let b = text.position(from: text.beginningOfDocument, offset: to) {
                text.selectedTextRange = text.textRange(from: a, to: b)
            }
        case "isFocused":
            let input = try Self.input(in: view, ref: ref)
            return try WireMap.encode(["focused": .flag(input.isFirstResponder)])
        default:
            throw ViewRefError("Unknown view ref method \(method)")
        }
        return Data()
    }

    /// `x`,`y` in the parent (transform-free layout frame); `windowX`/`windowY`
    /// in the window (transforms and scrolling included); `pageX`/`pageY` in
    /// the window's root view; all points.
    static func frame(of view: UIView, ref: String) throws -> [String: Double] {
        guard let window = view.window, view.bounds.width > 0 || view.bounds.height > 0 else {
            throw ViewRefError("The view \(ref) has not been laid out.")
        }
        let inWindow = view.convert(view.bounds, to: nil)
        let root = window.rootViewController?.view ?? window
        let inRoot = view.convert(view.bounds, to: root)
        let center = view.center
        let size = view.bounds.size
        return [
            "x": Double(center.x - size.width / 2),
            "y": Double(center.y - size.height / 2),
            "width": Double(size.width),
            "height": Double(size.height),
            "windowX": Double(inWindow.minX),
            "windowY": Double(inWindow.minY),
            "pageX": Double(inRoot.minX),
            "pageY": Double(inRoot.minY),
        ]
    }

    static func input(in view: UIView, ref: String) throws -> UIView {
        if let match = firstInput(view) { return match }
        throw ViewRefError("The view \(ref) is not a text input.")
    }

    private static func firstInput(_ view: UIView) -> UIView? {
        if view is UITextField { return view }
        if let area = view as? UITextView, area.isEditable { return area }
        for child in view.subviews {
            if let match = firstInput(child) { return match }
        }
        return nil
    }

    static func isValidRef(_ ref: String) -> Bool {
        ref.range(of: "^[A-Za-z0-9_.:-]{1,64}$", options: .regularExpression) != nil
    }

    private static func integer(_ value: WireValue?) -> Int {
        if case let .integer(number)? = value { return Int(max(0, min(number, Int64(Int32.max)))) }
        return 0
    }
}

private struct ViewRefError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
