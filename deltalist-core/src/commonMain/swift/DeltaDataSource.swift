#if canImport(UIKit)
import UIKit
import Combine

/// Generic UICollectionView data source that observes a DeltaList (Kotlin Flow<Delta<T>>).
/// Equivalent to Android's DeltaAdapter. Supports both regular lists and soft/paginated lists.
///
/// Uses direct UICollectionViewDataSource implementation with performBatchUpdates for efficient updates.
///
/// CRITICAL: Never access delta.items directly! It triggers NSArray bridging which iterates the
/// ENTIRE list - catastrophic for soft lists. Always use delta.loadedItems() and delta.totalSize().
///
/// For soft/paginated lists, provide a `loadingCellProvider` to display loading placeholders.
/// The data source will automatically trigger loads when unloaded items become visible.
@available(iOS 14.0, *)
@MainActor
public class DeltaCollectionDataSource<T: AnyObject>: NSObject,
    UICollectionViewDataSource,
    UICollectionViewDelegate
{
    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell
    public typealias LoadingCellProvider = (UICollectionView, IndexPath) -> UICollectionViewCell
    public typealias SupplementaryViewProvider = (UICollectionView, String, IndexPath) -> UICollectionReusableView?

    // MARK: - Properties

    private weak var collectionView: UICollectionView?
    private(set) public var items: [T] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false

    // Soft list support - store the current delta for pagination methods
    private var currentDelta: AnyObject?

    private let cellProvider: CellProvider
    private let loadingCellProvider: LoadingCellProvider?
    private var supplementaryViewProvider: SupplementaryViewProvider?

    /// The total size of the list (including unloaded items for soft lists).
    private(set) public var totalSize: Int = 0

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    /// Callback when a cell is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    // MARK: - Initialization

    public init(
        collectionView: UICollectionView,
        cellProvider: @escaping CellProvider,
        loadingCellProvider: LoadingCellProvider? = nil
    ) {
        self.collectionView = collectionView
        self.cellProvider = cellProvider
        self.loadingCellProvider = loadingCellProvider
        super.init()

        collectionView.dataSource = self
        collectionView.delegate = self
    }

    // MARK: - Binding

    /// Starts collecting deltas from a typed stream and applying them to the collection view.
    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    guard let self = self else { break }
                    self.applyDelta(delta)
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Starts collecting deltas from an erased stream (for type-erased Kotlin flows).
    public func bind(erased stream: some AsyncSequence) {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await value in stream {
                    if Task.isCancelled { break }
                    guard let self = self else { break }

                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    } else if let delta = value as? Delta<AnyObject> {
                        self.applyDeltaErased(delta)
                    } else {
                        // Cross-module compatibility
                        self.applyDeltaAny(value as AnyObject)
                    }
                }
            } catch {
                // Stream completed or was cancelled
            }
        }
    }

    /// Manually apply a delta value. Use this when you need to handle flow collection yourself
    /// (e.g., for protocol types like MoveableDeltaList that don't get AsyncSequence conformance).
    public func apply(delta: Any) {
        print("[DeltaDataSource] apply(delta:) called with type: \(type(of: delta))")
        if let typedDelta = delta as? Delta<T> {
            print("[DeltaDataSource] Using applyDelta (typed)")
            applyDelta(typedDelta)
        } else if let anyDelta = delta as? Delta<AnyObject> {
            print("[DeltaDataSource] Using applyDeltaErased")
            applyDeltaErased(anyDelta)
        } else {
            print("[DeltaDataSource] Using applyDeltaAny")
            applyDeltaAny(delta as AnyObject)
        }
    }

    /// Stops collecting deltas.
    public func unbind() {
        task?.cancel()
        task = nil
    }

    /// Sets the binding task for external collectors (e.g., MoveableDeltaList extensions).
    public func setBindingTask(_ newTask: Task<Void, Never>) {
        task = newTask
    }

    // MARK: - Delta Application

    private func applyDelta(_ delta: Delta<T>) {
        currentDelta = delta

        // Use loadedItems() to safely get only loaded items without triggering bridging
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }

        // Use totalSize() for soft lists
        totalSize = Int(delta.totalSize())

        onItemsChanged?(items)
        applyChange(delta.change)

        // Continue loading any visible cells that are still unloaded
        triggerLoadsForVisibleCells()
    }

    private func applyDeltaErased(_ delta: Delta<AnyObject>) {
        currentDelta = delta

        // Use loadedItems() to safely get only loaded items without triggering bridging
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }

        // Use totalSize() for soft lists
        totalSize = Int(delta.totalSize())

        onItemsChanged?(items)
        applyChange(delta.change)

        // Continue loading any visible cells that are still unloaded
        triggerLoadsForVisibleCells()
    }

    /// Apply delta from any type (cross-module compatibility)
    private func applyDeltaAny(_ delta: AnyObject) {
        currentDelta = delta

        // NEVER access "items" property - it triggers the bridging catastrophe!
        // Use loadedItems() method instead
        var extractedItems: [T] = []

        // Try calling loadedItems() method via protocol
        if let loadedArray = (delta as? DeltaProtocol)?.loadedItems() as? [AnyObject] {
            extractedItems = loadedArray.compactMap { $0 as? T }
        }

        items = extractedItems

        // Get totalSize for soft lists
        if let deltaProto = delta as? DeltaProtocol {
            totalSize = Int(deltaProto.totalSize())
        } else {
            totalSize = items.count
        }

        onItemsChanged?(items)

        // Get change and apply
        if let deltaProto = delta as? DeltaProtocol {
            applyChange(deltaProto.change)
        } else {
            // On first data or unknown, always reload
            if !hasReceivedInitialData {
                hasReceivedInitialData = true
            }
            collectionView?.reloadData()
        }

        // Continue loading any visible cells that are still unloaded
        triggerLoadsForVisibleCells()
    }

    private func applyChange(_ change: Change) {
        guard let collectionView = collectionView else { return }

        // On first data, always reload to sync collection view state
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            collectionView.reloadData()
            return
        }

        // Use direct type casting with nested Kotlin type names
        if change is Change.Reload {
            print("[DeltaDataSource] Applying Reload")
            collectionView.reloadData()
        } else if let mutations = change as? Change.Mutations {
            print("[DeltaDataSource] Applying Mutations: \(mutations.operations.count) operations")
            for (idx, operation) in mutations.operations.enumerated() {
                if let move = operation as? Mutation.Move {
                    print("[DeltaDataSource]   [\(idx)] Move: from=\(move.fromIndex) to=\(move.toIndex) count=\(move.count)")
                } else if let insert = operation as? Mutation.Insert {
                    print("[DeltaDataSource]   [\(idx)] Insert: index=\(insert.index) count=\(insert.count)")
                } else if let remove = operation as? Mutation.Remove {
                    print("[DeltaDataSource]   [\(idx)] Remove: index=\(remove.index) count=\(remove.count)")
                } else if let update = operation as? Mutation.Update {
                    print("[DeltaDataSource]   [\(idx)] Update: index=\(update.index) count=\(update.count)")
                } else {
                    print("[DeltaDataSource]   [\(idx)] Unknown: \(type(of: operation))")
                }
            }

            collectionView.performBatchUpdates {
                for operation in mutations.operations {
                    if let insert = operation as? Mutation.Insert {
                        let indexPaths = (0..<Int(insert.count)).map {
                            IndexPath(item: Int(insert.index) + $0, section: 0)
                        }
                        collectionView.insertItems(at: indexPaths)
                    } else if let remove = operation as? Mutation.Remove {
                        let indexPaths = (0..<Int(remove.count)).map {
                            IndexPath(item: Int(remove.index) + $0, section: 0)
                        }
                        collectionView.deleteItems(at: indexPaths)
                    } else if let update = operation as? Mutation.Update {
                        let indexPaths = (0..<Int(update.count)).map {
                            IndexPath(item: Int(update.index) + $0, section: 0)
                        }
                        collectionView.reloadItems(at: indexPaths)
                    } else if let move = operation as? Mutation.Move {
                        for i in 0..<Int(move.count) {
                            let from = IndexPath(item: Int(move.fromIndex) + i, section: 0)
                            let to = IndexPath(item: Int(move.toIndex) + i, section: 0)
                            collectionView.moveItem(at: from, to: to)
                        }
                    }
                }
            }
        } else {
            // Unknown change type, reload
            print("[DeltaDataSource] Unknown change type: \(type(of: change)), reloading")
            collectionView.reloadData()
        }
    }

    // MARK: - Soft List Support

    /// Returns true if the item at the given index is loaded (for soft lists).
    public func isLoadedAt(index: Int) -> Bool {
        if let delta = currentDelta as? Delta<T> {
            return delta.isLoadedAt(index: Int32(index))
        }
        if let delta = currentDelta as? Delta<AnyObject> {
            return delta.isLoadedAt(index: Int32(index))
        }
        if let deltaProto = currentDelta as? DeltaProtocol {
            return deltaProto.isLoadedAt(index: Int32(index))
        }
        return index < items.count
    }

    /// Returns the loaded item at the given index, or nil if not loaded (for soft lists).
    public func getLoadedItemAt(index: Int) -> T? {
        if let delta = currentDelta as? Delta<T> {
            return delta.getLoadedItemAt(index: Int32(index)) as? T
        }
        if let delta = currentDelta as? Delta<AnyObject> {
            return delta.getLoadedItemAt(index: Int32(index)) as? T
        }
        if let deltaProto = currentDelta as? DeltaProtocol {
            return deltaProto.getLoadedItemAt(index: Int32(index)) as? T
        }
        return index < items.count ? items[index] : nil
    }

    /// Triggers loading at the given index (for soft lists).
    public func triggerLoadAt(index: Int) {
        if let delta = currentDelta as? Delta<T> {
            delta.triggerLoadAt(index: Int32(index))
            return
        }
        if let delta = currentDelta as? Delta<AnyObject> {
            delta.triggerLoadAt(index: Int32(index))
            return
        }
        if let deltaProto = currentDelta as? DeltaProtocol {
            deltaProto.triggerLoadAt(index: Int32(index))
            return
        }
    }

    /// Triggers loading for all visible cells that are not yet loaded.
    private func triggerLoadsForVisibleCells() {
        guard loadingCellProvider != nil else { return }
        guard let collectionView = collectionView else { return }

        for indexPath in collectionView.indexPathsForVisibleItems {
            let index = indexPath.item
            if !isLoadedAt(index: index) {
                triggerLoadAt(index: index)
            }
        }
    }

    // MARK: - UICollectionViewDataSource

    public func numberOfSections(in collectionView: UICollectionView) -> Int {
        return 1
    }

    public func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return totalSize
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let index = indexPath.item

        if let item = getLoadedItemAt(index: index) {
            return cellProvider(collectionView, indexPath, item)
        } else if let loadingProvider = loadingCellProvider {
            return loadingProvider(collectionView, indexPath)
        } else if index < items.count {
            return cellProvider(collectionView, indexPath, items[index])
        } else {
            fatalError("Index out of bounds: \(index) >= \(items.count), totalSize: \(totalSize)")
        }
    }

    public func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        return supplementaryViewProvider?(collectionView, kind, indexPath) ?? UICollectionReusableView()
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        let index = indexPath.item
        if !isLoadedAt(index: index) {
            triggerLoadAt(index: index)
        }
    }

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Subclasses can override to handle lazy item release
    }

    public func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if let item = getLoadedItemAt(index: indexPath.item) {
            onItemSelected?(indexPath, item)
        }
        collectionView.deselectItem(at: indexPath, animated: true)
    }

    // MARK: - Supplementary Views

    public func setSupplementaryViewProvider(_ provider: SupplementaryViewProvider?) {
        self.supplementaryViewProvider = provider
    }

    // MARK: - Item Access

    public func item(at indexPath: IndexPath) -> T? {
        return getLoadedItemAt(index: indexPath.item)
    }

    public func item(at index: Int) -> T? {
        return getLoadedItemAt(index: index)
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Sectioned Delta Collection Data Source

/// Traditional UICollectionViewDataSource that observes a SectionedDeltaList.
/// NO DiffableDataSource - direct data source/delegate implementation with performBatchUpdates.
@available(iOS 14.0, *)
@MainActor
public class SectionedDeltaCollectionDataSource<H: AnyObject, T: AnyObject>: NSObject,
    UICollectionViewDataSource,
    UICollectionViewDelegate
{
    // MARK: - Types

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell
    public typealias HeaderProvider = (UICollectionView, IndexPath, H) -> UICollectionReusableView

    // MARK: - Section Data

    public struct SectionData {
        public let header: H
        public var items: [T]

        public init(header: H, items: [T]) {
            self.header = header
            self.items = items
        }
    }

    // MARK: - Properties

    private weak var collectionView: UICollectionView?
    private(set) public var sections: [SectionData] = []
    private var task: Task<Void, Never>?
    private var hasReceivedInitialData = false

    private let cellProvider: CellProvider
    private let headerProvider: HeaderProvider?

    /// Callback when sections are updated.
    public var onSectionsChanged: (([SectionData]) -> Void)?

    /// Callback when a cell is selected.
    public var onItemSelected: ((IndexPath, T) -> Void)?

    /// Callback when a header is tapped.
    public var onHeaderSelected: ((Int, H) -> Void)?

    // MARK: - Initialization

    public init(
        collectionView: UICollectionView,
        cellProvider: @escaping CellProvider,
        headerProvider: HeaderProvider? = nil
    ) {
        self.collectionView = collectionView
        self.cellProvider = cellProvider
        self.headerProvider = headerProvider
        super.init()

        collectionView.dataSource = self
        collectionView.delegate = self
    }

    // MARK: - Binding

    public func bind(to flow: some AsyncSequence) {
        unbind()
        hasReceivedInitialData = false
        task = Task { @MainActor [weak self] in
            do {
                for try await value in flow {
                    if Task.isCancelled { break }
                    guard let self = self else { break }

                    // Debug: print actual type
                    print("[SectionedDataSource] Received value of type: \(type(of: value))")

                    if let sectionedDelta = value as? SectionedDelta<H, T> {
                        print("[SectionedDataSource] Cast to SectionedDelta<H, T> succeeded")
                        self.applySectionedDelta(sectionedDelta)
                    } else if let sectionedDelta = value as? SectionedDelta<AnyObject, AnyObject> {
                        print("[SectionedDataSource] Cast to SectionedDelta<AnyObject, AnyObject> succeeded")
                        self.applySectionedDeltaErased(sectionedDelta)
                    } else {
                        print("[SectionedDataSource] Falling back to applySectionedDeltaAny")
                        // Cross-module compatibility
                        self.applySectionedDeltaAny(value as AnyObject)
                    }
                }
            } catch {
                print("[SectionedDataSource] Error: \(error)")
            }
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    // MARK: - Delta Application
    // CRITICAL: Never access delta.sections directly! Use Kotlin helper methods to avoid bridging.

    private func applySectionedDelta(_ delta: SectionedDelta<H, T>) {
        // Use Kotlin helper methods to avoid bridging catastrophe
        let sectionCount = Int(delta.sectionCount())
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(sectionIdx)) as? H else { continue }
            // Get items one by one to avoid list bridging
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(sectionIdx)))
            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applySectionedDeltaErased(_ delta: SectionedDelta<AnyObject, AnyObject>) {
        // Use Kotlin helper methods to avoid bridging catastrophe
        let sectionCount = Int(delta.sectionCount())
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(sectionIdx)) as? H else { continue }
            // Get items one by one to avoid list bridging
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(sectionIdx)))
            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    private func applySectionedDeltaAny(_ delta: AnyObject) {
        // Cross-module fallback: use Obj-C runtime to call methods directly
        // This handles cases like DemoCoreSectionedDelta where generic casts fail

        print("[SectionedDataSource] applySectionedDeltaAny - type: \(type(of: delta))")

        // Use Obj-C runtime directly - don't use KVC as it doesn't work with Kotlin classes
        let sectionCount = callSectionCountViaRuntime(delta)

        if sectionCount == 0 {
            print("[SectionedDataSource] Section count is 0 or method not found")
            // Check if we got 0 because there are no sections, or because the call failed
            // Try one more cast approach
            if let anyDelta = delta as? SectionedDelta<AnyObject, AnyObject> {
                let count = Int(anyDelta.sectionCount())
                if count > 0 {
                    extractAndApplySections(from: anyDelta, sectionCount: count)
                    return
                }
            }
            sections = []
            onSectionsChanged?([])
            collectionView?.reloadData()
            return
        }

        print("[SectionedDataSource] Section count: \(sectionCount)")

        // Extract sections using Obj-C runtime calls
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = callGetHeaderAt(delta, sectionIndex: Int32(sectionIdx)) as? H else {
                print("[SectionedDataSource] Could not get header at \(sectionIdx)")
                continue
            }

            let itemCount = callGetItemCountAt(delta, sectionIndex: Int32(sectionIdx))
            print("[SectionedDataSource] Section \(sectionIdx) has \(itemCount) items")

            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = callGetItemAt(delta, sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        // Get change via Obj-C runtime
        let change = callGetChange(delta)

        // On first data, always reload
        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            sections = newSections
            onSectionsChanged?(newSections)
            collectionView?.reloadData()
            return
        }

        sections = newSections
        onSectionsChanged?(newSections)

        if let change = change {
            applySectionedChange(change)
        } else {
            collectionView?.reloadData()
        }
    }

    private func extractAndApplySections(from delta: SectionedDelta<AnyObject, AnyObject>, sectionCount: Int) {
        var newSections: [SectionData] = []
        for sectionIdx in 0..<sectionCount {
            guard let header = delta.getHeaderAt(sectionIndex: Int32(sectionIdx)) as? H else { continue }
            let itemCount = Int(delta.getItemCountAt(sectionIndex: Int32(sectionIdx)))
            var items: [T] = []
            for itemIdx in 0..<itemCount {
                if let item = delta.getItemAt(sectionIndex: Int32(sectionIdx), itemIndex: Int32(itemIdx)) as? T {
                    items.append(item)
                }
            }
            newSections.append(SectionData(header: header, items: items))
        }

        applyChanges(newSections: newSections, change: delta.change)
    }

    /// Call sectionCount via ObjC runtime for Int32 return type
    private func callSectionCountViaRuntime(_ obj: AnyObject) -> Int {
        typealias MethodType = @convention(c) (AnyObject, Selector) -> Int32
        let sel = Selector(("sectionCount"))
        guard obj.responds(to: sel),
              let method = class_getInstanceMethod(type(of: obj), sel) else {
            print("[SectionedDataSource] sectionCount selector not found")
            return 0
        }
        let imp = method_getImplementation(method)
        let fn = unsafeBitCast(imp, to: MethodType.self)
        return Int(fn(obj, sel))
    }

    /// Call change property via ObjC runtime
    private func callGetChange(_ obj: AnyObject) -> SectionedChange? {
        typealias MethodType = @convention(c) (AnyObject, Selector) -> AnyObject?
        let sel = Selector(("change"))
        guard obj.responds(to: sel),
              let method = class_getInstanceMethod(type(of: obj), sel) else {
            return nil
        }
        let imp = method_getImplementation(method)
        let fn = unsafeBitCast(imp, to: MethodType.self)
        return fn(obj, sel) as? SectionedChange
    }

    /// Call getHeaderAt via ObjC runtime
    private func callGetHeaderAt(_ obj: AnyObject, sectionIndex: Int32) -> Any? {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32) -> AnyObject?
        let sel = Selector(("getHeaderAtSectionIndex:"))
        guard obj.responds(to: sel),
              let method = class_getInstanceMethod(type(of: obj), sel) else {
            return nil
        }
        let imp = method_getImplementation(method)
        let fn = unsafeBitCast(imp, to: MethodType.self)
        return fn(obj, sel, sectionIndex)
    }

    /// Call getItemCountAt via ObjC runtime
    private func callGetItemCountAt(_ obj: AnyObject, sectionIndex: Int32) -> Int {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32) -> Int32
        let sel = Selector(("getItemCountAtSectionIndex:"))
        guard obj.responds(to: sel),
              let method = class_getInstanceMethod(type(of: obj), sel) else {
            return 0
        }
        let imp = method_getImplementation(method)
        let fn = unsafeBitCast(imp, to: MethodType.self)
        return Int(fn(obj, sel, sectionIndex))
    }

    /// Call getItemAt via ObjC runtime
    private func callGetItemAt(_ obj: AnyObject, sectionIndex: Int32, itemIndex: Int32) -> Any? {
        typealias MethodType = @convention(c) (AnyObject, Selector, Int32, Int32) -> AnyObject?
        let sel = Selector(("getItemAtSectionIndex:itemIndex:"))
        guard obj.responds(to: sel),
              let method = class_getInstanceMethod(type(of: obj), sel) else {
            return nil
        }
        let imp = method_getImplementation(method)
        let fn = unsafeBitCast(imp, to: MethodType.self)
        return fn(obj, sel, sectionIndex, itemIndex)
    }

    private func applyChanges(newSections: [SectionData], change: SectionedChange) {
        sections = newSections
        onSectionsChanged?(newSections)

        guard let collectionView = collectionView else { return }

        if !hasReceivedInitialData {
            hasReceivedInitialData = true
            collectionView.reloadData()
            return
        }

        applySectionedChange(change)
    }

    private func applySectionedChange(_ change: SectionedChange) {
        guard let collectionView = collectionView else { return }

        // Use direct type casting with nested Kotlin type names
        if change is SectionedChange.Reload {
            collectionView.reloadData()
        } else if let sectionChanges = change as? SectionedChange.Sections {
            collectionView.performBatchUpdates {
                for mutation in sectionChanges.mutations {
                    if let insert = mutation as? SectionMutation.Insert {
                        let sectionIndices = IndexSet(Int(insert.index)..<Int(insert.index + insert.count))
                        collectionView.insertSections(sectionIndices)
                    } else if let remove = mutation as? SectionMutation.Remove {
                        let sectionIndices = IndexSet(Int(remove.index)..<Int(remove.index + remove.count))
                        collectionView.deleteSections(sectionIndices)
                    } else if let update = mutation as? SectionMutation.Update {
                        collectionView.reloadSections(IndexSet(integer: Int(update.index)))
                    } else if let move = mutation as? SectionMutation.Move {
                        collectionView.moveSection(Int(move.fromIndex), toSection: Int(move.toIndex))
                    }
                }
            }
        } else if let itemChanges = change as? SectionedChange.Items {
            let sectionIndex = Int(itemChanges.section)
            collectionView.performBatchUpdates {
                for mutation in itemChanges.mutations {
                    if let insert = mutation as? Mutation.Insert {
                        let indexPaths = (0..<Int(insert.count)).map {
                            IndexPath(item: Int(insert.index) + $0, section: sectionIndex)
                        }
                        collectionView.insertItems(at: indexPaths)
                    } else if let remove = mutation as? Mutation.Remove {
                        let indexPaths = (0..<Int(remove.count)).map {
                            IndexPath(item: Int(remove.index) + $0, section: sectionIndex)
                        }
                        collectionView.deleteItems(at: indexPaths)
                    } else if let update = mutation as? Mutation.Update {
                        let indexPaths = (0..<Int(update.count)).map {
                            IndexPath(item: Int(update.index) + $0, section: sectionIndex)
                        }
                        collectionView.reloadItems(at: indexPaths)
                    } else if let move = mutation as? Mutation.Move {
                        for i in 0..<Int(move.count) {
                            let from = IndexPath(item: Int(move.fromIndex) + i, section: sectionIndex)
                            let to = IndexPath(item: Int(move.toIndex) + i, section: sectionIndex)
                            collectionView.moveItem(at: from, to: to)
                        }
                    }
                }
            }
        } else {
            collectionView.reloadData()
        }
    }

    // MARK: - UICollectionViewDataSource

    public func numberOfSections(in collectionView: UICollectionView) -> Int {
        return sections.count
    }

    public func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        guard section < sections.count else { return 0 }
        return sections[section].items.count
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else {
            fatalError("Index out of bounds: section \(indexPath.section), item \(indexPath.item)")
        }
        let item = sections[indexPath.section].items[indexPath.item]
        return cellProvider(collectionView, indexPath, item)
    }

    public func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        guard kind == UICollectionView.elementKindSectionHeader,
              let headerProvider = headerProvider,
              indexPath.section < sections.count else {
            return UICollectionReusableView()
        }
        let header = sections[indexPath.section].header
        return headerProvider(collectionView, indexPath, header)
    }

    // MARK: - UICollectionViewDelegate

    public func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else { return }
        let item = sections[indexPath.section].items[indexPath.item]
        onItemSelected?(indexPath, item)
        collectionView.deselectItem(at: indexPath, animated: true)
    }

    // MARK: - Item Access

    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.section < sections.count,
              indexPath.item < sections[indexPath.section].items.count else { return nil }
        return sections[indexPath.section].items[indexPath.item]
    }

    public func header(at section: Int) -> H? {
        guard section < sections.count else { return nil }
        return sections[section].header
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Stable Delta Collection Data Source

/// Data source that uses stable IDs from StableItem types directly.
/// More efficient for lists using the withStableIds() operator.
@available(iOS 14.0, *)
@MainActor
public class StableDeltaCollectionDataSource<T: AnyObject>: NSObject, UICollectionViewDelegate {

    public typealias CellProvider = (UICollectionView, IndexPath, T) -> UICollectionViewCell?

    private weak var collectionView: UICollectionView?
    private var diffableDataSource: UICollectionViewDiffableDataSource<Int, Int32>!
    private var items: [T] = []
    private var itemsByStableId: [Int32: T] = [:]
    private var task: Task<Void, Never>?

    private let cellProvider: CellProvider
    private let stableIdExtractor: (T) -> Int32

    public var currentItems: [T] { items }

    /// Callback when items are updated.
    public var onItemsChanged: (([T]) -> Void)?

    public init(
        collectionView: UICollectionView,
        stableIdExtractor: @escaping (T) -> Int32,
        cellProvider: @escaping CellProvider
    ) {
        self.collectionView = collectionView
        self.stableIdExtractor = stableIdExtractor
        self.cellProvider = cellProvider
        super.init()

        setupDiffableDataSource(collectionView: collectionView)
        collectionView.delegate = self
    }

    private func setupDiffableDataSource(collectionView: UICollectionView) {
        diffableDataSource = UICollectionViewDiffableDataSource<Int, Int32>(
            collectionView: collectionView
        ) { [weak self] collectionView, indexPath, stableId in
            guard let self = self,
                  let item = self.itemsByStableId[stableId] else { return nil }
            return self.cellProvider(collectionView, indexPath, item)
        }
    }

    public func bind<S: AsyncSequence>(to stream: S) where S.Element == Delta<T> {
        unbind()
        task = Task { @MainActor in
            do {
                for try await delta in stream {
                    if Task.isCancelled { break }
                    self.applyDelta(delta)
                }
            } catch {}
        }
    }

    public func bind(erased stream: some AsyncSequence) {
        unbind()
        task = Task { @MainActor in
            do {
                for try await value in stream {
                    if Task.isCancelled { break }
                    if let delta = value as? Delta<T> {
                        self.applyDelta(delta)
                    } else if let delta = value as? Delta<AnyObject> {
                        self.applyDeltaErased(delta)
                    } else {
                        // Cross-module fallback: cast to base Delta type
                        self.applyDeltaAny(value as AnyObject)
                    }
                }
            } catch {}
        }
    }

    public func unbind() {
        task?.cancel()
        task = nil
    }

    private func applyDelta(_ delta: Delta<T>) {
        // Use loadedItems() to avoid bridging catastrophe
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating = !(delta.change is Change.Reload)
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    private func applyDeltaErased(_ delta: Delta<AnyObject>) {
        // Use loadedItems() to avoid bridging catastrophe
        let loadedItems = delta.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating = !(delta.change is Change.Reload)
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    private func applyDeltaAny(_ delta: AnyObject) {
        // Cross-module fallback: access Delta properties directly
        // Delta is a Kotlin class, so we can call its methods
        guard let deltaBase = delta as? Delta<NSObject> else {
            // Last resort: try to extract via method calls if it's some Delta variant
            if delta.responds(to: Selector(("loadedItems"))) {
                if let loadedArray = delta.perform(Selector(("loadedItems")))?.takeUnretainedValue() as? [AnyObject] {
                    items = loadedArray.compactMap { $0 as? T }
                    itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
                    onItemsChanged?(items)

                    var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
                    snapshot.appendSections([0])
                    snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)
                    diffableDataSource.apply(snapshot, animatingDifferences: false)
                }
            }
            return
        }

        let loadedItems = deltaBase.loadedItems()
        items = loadedItems.compactMap { $0 as? T }
        itemsByStableId = Dictionary(uniqueKeysWithValues: items.map { (stableIdExtractor($0), $0) })
        onItemsChanged?(items)

        var snapshot = NSDiffableDataSourceSnapshot<Int, Int32>()
        snapshot.appendSections([0])
        snapshot.appendItems(items.map { stableIdExtractor($0) }, toSection: 0)

        let animating = !(deltaBase.change is Change.Reload)
        diffableDataSource.apply(snapshot, animatingDifferences: animating)
    }

    public func item(at indexPath: IndexPath) -> T? {
        guard indexPath.item < items.count else { return nil }
        return items[indexPath.item]
    }

    public func item(at index: Int) -> T? {
        guard index < items.count else { return nil }
        return items[index]
    }

    public func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        // Subclasses can override
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - Protocol Interfaces for Cross-Module Compatibility

/// Protocol for accessing Delta properties without direct type coupling.
/// This protocol is used when generic type casts fail across module boundaries.
@objc public protocol DeltaProtocol {
    var change: Change { get }
    func loadedItems() -> [Any]
    func totalSize() -> Int32
    func isLoadedAt(index: Int32) -> Bool
    func getLoadedItemAt(index: Int32) -> Any?
    func triggerLoadAt(index: Int32)
}

/// Protocol for accessing SectionedDelta properties.
/// WARNING: Accessing the `sections` property triggers NSArray bridging which
/// iterates the entire list. Use Kotlin helper methods when possible.
@objc public protocol SectionedDeltaProtocol {
    var change: SectionedChange { get }
    var sections: [Any] { get }
}

/// Protocol for accessing Section properties.
/// WARNING: Accessing the `items` property triggers NSArray bridging.
@objc public protocol SectionProtocol {
    var header: Any? { get }
    var items: [Any] { get }
}
#endif
