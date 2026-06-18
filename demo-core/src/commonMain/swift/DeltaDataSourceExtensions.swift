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

        // Funnel emissions through a single serial AsyncStream consumed in arrival order.
        // The previous implementation spawned a fresh `Task { @MainActor }` per emission;
        // unstructured main-actor tasks are not ordered relative to one another, so delta
        // N+1 could apply before N. Since each delta's mutations assume the prior delta
        // already landed, that desync corrupts state. A single `for await` loop guarantees
        // strict ordering, matching the serial `for try await` path used for typed streams.
        var continuationRef: AsyncStream<Any>.Continuation!
        let stream = AsyncStream<Any> { continuationRef = $0 }
        let continuation = continuationRef!

        let collector = MoveableFlowCollector { value in
            continuation.yield(value)
        }

        let collectTask = Task {
            do {
                try await moveable.collect(collector: collector)
            } catch {
                // Flow completed or was cancelled
            }
            continuation.finish()
        }

        let task = Task { @MainActor [weak self] in
            defer { collectTask.cancel() }
            for await value in stream {
                guard let self = self else { break }
                self.apply(delta: value)
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
    private let onValue: @Sendable (Any) -> Void

    init(onValue: @escaping @Sendable (Any) -> Void) {
        self.onValue = onValue
    }

    // Completion handler version (required by SKIE). Forwarding to the serial stream is
    // synchronous and ordered, so the next emission is processed only after this one is
    // enqueued in order.
    @objc func __emit(value: Any?, completionHandler: @escaping @Sendable ((any Error)?) -> Void) {
        if let value = value {
            onValue(value)
        }
        completionHandler(nil)
    }
}
#endif
