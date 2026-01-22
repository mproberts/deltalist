import UIKit
import Combine

/// UIKit implementation of the sectioned list demo using native UICollectionView sections.
@MainActor
class SectionedListViewController: UIViewController {
    private let viewModel: SectionedListViewModelAdapter
    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<String, String>!
    private var cancellables = Set<AnyCancellable>()

    var selectedSectionIndex: Int = -1 {
        didSet {
            if oldValue != selectedSectionIndex {
                updateSnapshot()
            }
        }
    }

    var onSectionSelected: ((Int) -> Void)?

    init(viewModel: SectionedListViewModelAdapter) {
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
        bindViewModel()
    }

    private func setupCollectionView() {
        let layout = createLayout()
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        collectionView.delegate = self
        view.addSubview(collectionView)
    }

    private func createLayout() -> UICollectionViewLayout {
        var config = UICollectionLayoutListConfiguration(appearance: .plain)
        config.showsSeparators = true
        config.headerMode = .supplementary
        return UICollectionViewCompositionalLayout.list(using: config)
    }

    private func setupDataSource() {
        // Item cell registration
        let itemRegistration = UICollectionView.CellRegistration<SectionItemCell, String> { [weak self] cell, indexPath, itemId in
            guard let self = self,
                  indexPath.section < self.viewModel.sections.count else { return }
            let section = self.viewModel.sections[indexPath.section]
            if let item = section.items.first(where: { $0.id == itemId }) {
                cell.configure(with: item)
            }
        }

        dataSource = UICollectionViewDiffableDataSource<String, String>(collectionView: collectionView) { collectionView, indexPath, itemId in
            collectionView.dequeueConfiguredReusableCell(using: itemRegistration, for: indexPath, item: itemId)
        }

        // Section header registration
        let headerRegistration = UICollectionView.SupplementaryRegistration<SectionHeaderView>(
            elementKind: UICollectionView.elementKindSectionHeader
        ) { [weak self] headerView, elementKind, indexPath in
            guard let self = self,
                  indexPath.section < self.viewModel.sections.count else { return }
            let section = self.viewModel.sections[indexPath.section]
            let isSelected = indexPath.section == self.selectedSectionIndex
            headerView.configure(with: section.header, isSelected: isSelected)
            headerView.setTapHandler { [weak self] in
                self?.onSectionSelected?(indexPath.section)
            }
        }

        dataSource.supplementaryViewProvider = { collectionView, kind, indexPath in
            collectionView.dequeueConfiguredReusableSupplementary(using: headerRegistration, for: indexPath)
        }
    }

    private func bindViewModel() {
        viewModel.$sections
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.updateSnapshot()
            }
            .store(in: &cancellables)
    }

    private func updateSnapshot() {
        var snapshot = NSDiffableDataSourceSnapshot<String, String>()

        for section in viewModel.sections {
            snapshot.appendSections([section.id])
            snapshot.appendItems(section.items.map { $0.id }, toSection: section.id)
        }

        dataSource.apply(snapshot, animatingDifferences: true)
    }
}

// MARK: - UICollectionViewDelegate

extension SectionedListViewController: UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: true)
        // Item selection - could be extended if needed
    }
}

// MARK: - Section Header View

private class SectionHeaderView: UICollectionReusableView {
    private var titleLabel: UILabel!
    private var tapHandler: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.textColor = .white
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        addSubview(titleLabel)
        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            titleLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            titleLabel.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            titleLabel.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12)
        ])

        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        addGestureRecognizer(tapGesture)
    }

    func configure(with header: SectionHeaderWrapper, isSelected: Bool) {
        titleLabel.text = header.title
        backgroundColor = UIColor(header.color).withAlphaComponent(isSelected ? 1.0 : 0.8)
    }

    func setTapHandler(_ handler: @escaping () -> Void) {
        self.tapHandler = handler
    }

    @objc private func handleTap() {
        tapHandler?()
    }
}

// MARK: - Section Item Cell

private class SectionItemCell: UICollectionViewListCell {
    private var titleLabel: UILabel!
    private var idLabel: UILabel!

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupViews() {
        let stackView = UIStackView()
        stackView.axis = .vertical
        stackView.spacing = 2
        stackView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel = UILabel()
        titleLabel.font = .preferredFont(forTextStyle: .body)

        idLabel = UILabel()
        idLabel.font = .preferredFont(forTextStyle: .caption1)
        idLabel.textColor = .secondaryLabel

        stackView.addArrangedSubview(titleLabel)
        stackView.addArrangedSubview(idLabel)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(with item: ItemWrapper) {
        titleLabel.text = item.title
        idLabel.text = "ID: \(item.id.prefix(8))..."
    }
}
