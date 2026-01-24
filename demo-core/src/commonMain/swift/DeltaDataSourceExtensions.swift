#if canImport(UIKit)
import UIKit

/// Extensions for DeltaCollectionDataSource to support MoveableDeltaList.
/// This file is in demo-core so it has access to DemoCore's types (MoveableDeltaList, FlowCollector, etc.)
/// which are re-exported by SKIE with module-specific names.

@available(iOS 14.0, *)
extension DeltaCollectionDataSource {

    /// Binds to a MoveableDeltaList using FlowCollector.
    /// Use this for MoveableDeltaList which doesn't get AsyncSequence conformance from SKIE.
    @MainActor
    public func bind(moveable: any MoveableDeltaList) {
        unbind()

        let task = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = MoveableFlowCollector { [weak self] value in
                guard let self = self else { return }
                self.apply(delta: value)
            }

            do {
                try await moveable.collect(collector: collector)
            } catch {
                // Flow completed or was cancelled
            }
        }

        // Store the task for cancellation
        setBindingTask(task)
    }
}

/// FlowCollector implementation that forwards values to a callback.
/// Uses __emit (double underscore) as required by SKIE-generated FlowCollector protocol.
@available(iOS 14.0, *)
private final class MoveableFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onValue: @MainActor @Sendable (Any) -> Void

    init(onValue: @escaping @MainActor @Sendable (Any) -> Void) {
        self.onValue = onValue
    }

    // Completion handler version (required by SKIE)
    @objc func __emit(value: Any?, completionHandler: @escaping @Sendable ((any Error)?) -> Void) {
        guard let value = value else {
            completionHandler(nil)
            return
        }
        let callback = onValue
        Task { @MainActor in
            callback(value)
            completionHandler(nil)
        }
    }
}
#endif
