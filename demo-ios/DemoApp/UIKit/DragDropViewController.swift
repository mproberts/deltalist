import UIKit
import DemoCore

/// UIKit implementation of the drag and drop demo.
/// Uses DeltaCollectionDataSource with MoveableDeltaList.
@MainActor
class DragDropViewController: UIViewController {
    private let viewModel: DragDropViewModel
    private var collectionView: UICollectionView!
    private var dataSource: DeltaCollectionDataSource<Item>!
    private var dragSourceIndex: Int?
    private var dropDestinationIndex: Int?
    private var isDragging: Bool = false

    init(viewModel: DragDropViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        setupCollectionView()
        setupDataSource()
        setupDragAndDrop()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        dataSource.unbind()
    }

    private func setupCollectionView() {
        let layout = createLayout()
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        view.addSubview(collectionView)
    }

    private func createLayout() -> UICollectionViewLayout {
        var config = UICollectionLayoutListConfiguration(appearance: .plain)
        config.showsSeparators = true
        return UICollectionViewCompositionalLayout.list(using: config)
    }

    private func setupDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<DragDropCell, Item> { [weak self] cell, indexPath, item in
            let canMove = self?.canMove(item: item) ?? false
            cell.configure(with: item, canMove: canMove)
        }

        dataSource = DeltaCollectionDataSource<Item>(
            collectionView: collectionView,
            cellProvider: { collectionView, indexPath, item in
                collectionView.dequeueConfiguredReusableCell(using: cellRegistration, for: indexPath, item: item)
            }
        )

        // Bind using the MoveableDeltaList extension (handles FlowCollector internally)
        dataSource.bind(moveable: viewModel.items)
    }

    private func setupDragAndDrop() {
        collectionView.dragDelegate = self
        collectionView.dropDelegate = self
        collectionView.dragInteractionEnabled = true
    }

    // MARK: - Helpers

    private func canMove(item: Item) -> Bool {
        !item.title.contains("Pinned")
    }
}

// MARK: - UICollectionViewDragDelegate

extension DragDropViewController: UICollectionViewDragDelegate {
    func collectionView(_ collectionView: UICollectionView, itemsForBeginning session: UIDragSession, at indexPath: IndexPath) -> [UIDragItem] {
        guard let item = dataSource.item(at: indexPath) else { return [] }

        // Don't allow dragging pinned items
        guard canMove(item: item) else { return [] }

        isDragging = true
        dragSourceIndex = indexPath.item
        dropDestinationIndex = indexPath.item

        print("[DragDrop] beginDrag at index \(indexPath.item)")
        // Begin drag in Kotlin model - check return value to ensure state was updated
        guard viewModel.items.beginDrag(index: Int32(indexPath.item)) else {
            print("[DragDrop] beginDrag failed, aborting drag")
            isDragging = false
            dragSourceIndex = nil
            dropDestinationIndex = nil
            return []
        }

        let itemProvider = NSItemProvider(object: item.id as NSString)
        let dragItem = UIDragItem(itemProvider: itemProvider)
        dragItem.localObject = item
        return [dragItem]
    }

    func collectionView(_ collectionView: UICollectionView, dragSessionDidEnd session: UIDragSession) {
        // Commit will be called by drop delegate
    }
}

// MARK: - UICollectionViewDropDelegate

extension DragDropViewController: UICollectionViewDropDelegate {
    func collectionView(_ collectionView: UICollectionView, dropSessionDidUpdate session: UIDropSession, withDestinationIndexPath destinationIndexPath: IndexPath?) -> UICollectionViewDropProposal {
        guard collectionView.hasActiveDrag else {
            return UICollectionViewDropProposal(operation: .forbidden)
        }

        if let destPath = destinationIndexPath {
            if destPath.item != dropDestinationIndex {
                print("[DragDrop] dropSessionDidUpdate: destination changed to \(destPath.item)")
            }
            dropDestinationIndex = destPath.item
        }

        return UICollectionViewDropProposal(operation: .move, intent: .insertAtDestinationIndexPath)
    }

    func collectionView(_ collectionView: UICollectionView, performDropWith coordinator: UICollectionViewDropCoordinator) {
        print("[DragDrop] performDropWith called, dragSourceIndex=\(String(describing: dragSourceIndex))")
        guard let sourceIndex = dragSourceIndex else {
            print("[DragDrop] No dragSourceIndex, cancelling")
            viewModel.items.cancelDrag()
            isDragging = false
            dragSourceIndex = nil
            dropDestinationIndex = nil
            return
        }

        // Get destination from coordinator, fall back to tracked value
        let destIndex: Int
        if let destPath = coordinator.destinationIndexPath {
            destIndex = destPath.item
            print("[DragDrop] Using coordinator destination: \(destIndex)")
        } else if let tracked = dropDestinationIndex {
            destIndex = tracked
            print("[DragDrop] Using tracked destination: \(destIndex)")
        } else {
            // No valid destination, cancel
            print("[DragDrop] No valid destination, cancelling")
            viewModel.items.cancelDrag()
            isDragging = false
            dragSourceIndex = nil
            dropDestinationIndex = nil
            return
        }

        print("[DragDrop] Committing move from \(sourceIndex) to \(destIndex)")

        isDragging = false
        dragSourceIndex = nil
        dropDestinationIndex = nil

        // Use commitDrag(toIndex:) which skips the preview emission
        // UICollectionView handles the visual animation, we just need to persist
        Task {
            do {
                print("[DragDrop] Calling commitDrag(toIndex: \(destIndex))")
                let result = try await viewModel.items.commitDrag(toIndex: Int32(destIndex))
                print("[DragDrop] commitDrag() returned: \(result)")
            } catch {
                print("[DragDrop] commitDrag() failed: \(error)")
            }
        }
    }

    func collectionView(_ collectionView: UICollectionView, dropSessionDidEnd session: UIDropSession) {
        if isDragging {
            viewModel.items.cancelDrag()
            isDragging = false
            dragSourceIndex = nil
            dropDestinationIndex = nil
        }
    }
}

// MARK: - Drag Drop Cell

private class DragDropCell: UICollectionViewListCell {
    private var handleImageView: UIImageView!
    private var titleLabel: UILabel!
    private var subtitleLabel: UILabel!
    private var canMove: Bool = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        let stackView = UIStackView()
        stackView.axis = .horizontal
        stackView.spacing = 12
        stackView.alignment = .center
        stackView.translatesAutoresizingMaskIntoConstraints = false

        handleImageView = UIImageView(image: UIImage(systemName: "line.3.horizontal"))
        handleImageView.tintColor = .secondaryLabel
        handleImageView.setContentHuggingPriority(.required, for: .horizontal)

        let textStack = UIStackView()
        textStack.axis = .vertical
        textStack.spacing = 2

        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .body)

        subtitleLabel = UILabel()
        subtitleLabel.font = .preferredFont(forTextStyle: .caption1)
        subtitleLabel.textColor = .secondaryLabel

        textStack.addArrangedSubview(titleLabel)
        textStack.addArrangedSubview(subtitleLabel)

        stackView.addArrangedSubview(handleImageView)
        stackView.addArrangedSubview(textStack)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(with item: Item, canMove: Bool) {
        self.canMove = canMove
        titleLabel.text = item.title
        subtitleLabel.text = canMove ? "Long press to drag" : "Cannot be moved"
        handleImageView.isHidden = !canMove
        contentView.backgroundColor = canMove ? .clear : UIColor.systemRed.withAlphaComponent(0.1)
    }

    func setDragging(_ isDragging: Bool) {
        alpha = isDragging ? 0.7 : 1.0
        transform = isDragging ? CGAffineTransform(scaleX: 1.05, y: 1.05) : .identity
    }
}
