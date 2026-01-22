import Foundation
import SwiftUI
import DemoCore

/// Wraps a Kotlin SectionHeader for use in Swift.
struct SectionHeaderWrapper: Identifiable, Hashable {
    let id: String
    let title: String
    let color: Color

    init(kotlinHeader: SectionHeader) {
        self.id = kotlinHeader.title
        self.title = kotlinHeader.title
        // Convert ARGB Long to SwiftUI Color
        let argb = UInt64(kotlinHeader.color)
        let red = Double((argb >> 16) & 0xFF) / 255.0
        let green = Double((argb >> 8) & 0xFF) / 255.0
        let blue = Double(argb & 0xFF) / 255.0
        self.color = Color(red: red, green: green, blue: blue)
    }
}

/// Section wrapper containing a header and items.
struct ItemSectionWrapper: Identifiable {
    let id: String
    let header: SectionHeaderWrapper
    var items: [ItemWrapper]

    init?(kotlinSection: Any) {
        guard let section = kotlinSection as? DemoCore.Section<SectionHeader, Item>,
              let header = section.header else {
            return nil
        }
        self.header = SectionHeaderWrapper(kotlinHeader: header)
        self.id = self.header.id
        self.items = section.items.compactMap { item -> ItemWrapper? in
            guard let kotlinItem = item as? Item else { return nil }
            return ItemWrapper(kotlinItem: kotlinItem)
        }
    }
}

/// Wraps the shared Kotlin SectionedListViewModel for use in SwiftUI and UIKit.
@MainActor
class SectionedListViewModelAdapter: ObservableObject {
    private let viewModel = SectionedListViewModel()

    @Published private(set) var sections: [ItemSectionWrapper] = []

    private var sectionsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // Collect sections
        sectionsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = SectionedDeltaFlowCollector { [weak self] delta in
                guard let self = self else { return }
                self.sections = delta.sections.compactMap { section in
                    ItemSectionWrapper(kotlinSection: section)
                }
            }

            do {
                try await self.viewModel.sections.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }
    }

    func stopCollecting() {
        sectionsTask?.cancel()
    }

    // MARK: - Actions

    func addSection() {
        viewModel.addSection()
    }

    func removeSection(at index: Int) {
        viewModel.removeSection(index: Int32(index))
    }

    func addItemToSection(_ sectionIndex: Int) {
        viewModel.addItemToSection(sectionIndex: Int32(sectionIndex))
    }

    func removeItemFromSection(_ sectionIndex: Int, itemIndex: Int) {
        viewModel.removeItemFromSection(sectionIndex: Int32(sectionIndex), itemIndex: Int32(itemIndex))
    }

    func moveSection(from fromIndex: Int, to toIndex: Int) {
        viewModel.moveSection(fromIndex: Int32(fromIndex), toIndex: Int32(toIndex))
    }

    func clearSections() {
        viewModel.clearSections()
    }

    deinit {
        sectionsTask?.cancel()
    }
}

// MARK: - Flow Collectors

/// FlowCollector for SectionedDeltaList flows.
class SectionedDeltaFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onDelta: (SectionedDelta<AnyObject, AnyObject>) -> Void

    init(onDelta: @escaping (SectionedDelta<AnyObject, AnyObject>) -> Void) {
        self.onDelta = onDelta
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let delta = value as? SectionedDelta<AnyObject, AnyObject> {
            Task { @MainActor [self] in
                self.onDelta(delta)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}
