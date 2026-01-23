import UIKit
import DemoCore
//import DeltaListCore

/// UIKit implementation of the paginated list demo.
/// Uses DeltaCollectionDataSource with soft list support.
@MainActor
class PaginatedListViewController: UIViewController {
    private let viewModel: PaginatedListViewModel
    private var collectionView: UICollectionView!
    private var dataSource: DeltaCollectionDataSource<KotlinInt>!

    init(viewModel: PaginatedListViewModel) {
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
        // Register cells
        let numberCellRegistration = UICollectionView.CellRegistration<NumberCell, KotlinInt> { cell, indexPath, value in
            cell.configure(number: value.intValue, index: indexPath.item)
        }

        let loadingCellRegistration = UICollectionView.CellRegistration<LoadingCell, Void> { cell, indexPath, _ in
            cell.configure(index: indexPath.item)
        }

        // Create data source with soft list support
        dataSource = DeltaCollectionDataSource<KotlinInt>(
            collectionView: collectionView,
            cellProvider: { collectionView, indexPath, value in
                collectionView.dequeueConfiguredReusableCell(using: numberCellRegistration, for: indexPath, item: value)
            },
            loadingCellProvider: { collectionView, indexPath in
                collectionView.dequeueConfiguredReusableCell(using: loadingCellRegistration, for: indexPath, item: ())
            }
        )

        // Bind to the paginated flow
        dataSource.bind(erased: viewModel.paginatedNumbers)
    }
}

// MARK: - Number Cell

private class NumberCell: UICollectionViewListCell {
    private var numberLabel: UILabel!
    private var indexLabel: UILabel!

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
        stackView.translatesAutoresizingMaskIntoConstraints = false

        numberLabel = UILabel()
        numberLabel.font = .preferredFont(forTextStyle: .title2)

        indexLabel = UILabel()
        indexLabel.font = .preferredFont(forTextStyle: .caption1)
        indexLabel.textColor = .secondaryLabel

        stackView.addArrangedSubview(numberLabel)
        stackView.addArrangedSubview(UIView()) // Spacer
        stackView.addArrangedSubview(indexLabel)

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(number: Int, index: Int) {
        numberLabel.text = "#\(number)"
        indexLabel.text = "index: \(index)"
    }
}

// MARK: - Loading Cell

private class LoadingCell: UICollectionViewListCell {
    private var activityIndicator: UIActivityIndicatorView!
    private var label: UILabel!

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
        stackView.translatesAutoresizingMaskIntoConstraints = false

        activityIndicator = UIActivityIndicatorView(style: .medium)
        activityIndicator.startAnimating()

        label = UILabel()
        label.text = "Loading..."
        label.font = .preferredFont(forTextStyle: .body)
        label.textColor = .secondaryLabel

        stackView.addArrangedSubview(activityIndicator)
        stackView.addArrangedSubview(label)
        stackView.addArrangedSubview(UIView()) // Spacer

        contentView.addSubview(stackView)
        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            stackView.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor)
        ])
    }

    func configure(index: Int) {
        // Configure if needed
    }
}
