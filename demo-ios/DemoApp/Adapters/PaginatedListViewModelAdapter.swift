import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin PaginatedListViewModel for the auxiliary state and actions that sit
/// AROUND the soft list (loading indicator, counts, filter toggles). The soft list itself is bound
/// with the consolidated `DeltaList` wrapper in the view, so this adapter no longer collects the
/// delta stream or duplicates the soft-list accessors.
@MainActor
class PaginatedListViewModelAdapter: ObservableObject {
    let viewModel = PaginatedListViewModel()

    @Published private(set) var loadingDirection: DemoCore.LoadDirection? = nil
    @Published private(set) var loadedCount: Int = 0
    @Published private(set) var excludeDivisors: Set<Int> = []

    private var loadingDirectionTask: Task<Void, Never>?
    private var loadedCountTask: Task<Void, Never>?
    private var excludeDivisorsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // Collect loading direction using SKIE's StateFlow→AsyncSequence
        loadingDirectionTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await direction in self.viewModel.paginatedLoadingDirection {
                if Task.isCancelled { break }
                self.loadingDirection = direction
            }
        }

        // Collect loaded count
        loadedCountTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await count in self.viewModel.paginatedLoadedCount {
                if Task.isCancelled { break }
                self.loadedCount = count.intValue
            }
        }

        // Collect exclude divisors
        excludeDivisorsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await divisors in self.viewModel.excludeDivisors {
                if Task.isCancelled { break }
                self.excludeDivisors = Set(divisors.compactMap { ($0 as? NSNumber)?.intValue })
            }
        }
    }

    func stopCollecting() {
        loadingDirectionTask?.cancel()
        loadedCountTask?.cancel()
        excludeDivisorsTask?.cancel()
    }

    // MARK: - Actions

    func toggleDivisorFilter(_ divisor: Int) {
        viewModel.toggleDivisorFilter(divisor: Int32(divisor))
    }

    deinit {
        loadingDirectionTask?.cancel()
        loadedCountTask?.cancel()
        excludeDivisorsTask?.cancel()
    }
}
