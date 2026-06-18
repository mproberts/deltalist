import SwiftUI
import DemoCore
import DeltaListCore

/// Sectioned list demo screen with headers and items.
/// Uses the Kotlin ViewModel directly with the consolidated SectionedDeltaList wrapper.
struct SectionedListView: View {
    // Use Kotlin ViewModel directly - no adapter needed!
    private let viewModel = SectionedListViewModel()

    // Consolidated DeltaListCore wrapper; extracts via accessor methods (never touches
    // delta.sections) and is collected by `.task` below.
    @StateObject private var sectionList = DeltaListCore.SectionedDeltaList<SectionHeader, Item>()

    @State private var selectedTab = 0
    @State private var selectedSectionIndex: Int? = nil
    @State private var selectedItemIndex: Int? = nil

    private var sections: [ItemSectionWrapper] {
        sectionList.sections.map { ItemSectionWrapper(header: $0.header, items: $0.items) }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Tab selector
            Picker("View Type", selection: $selectedTab) {
                Text("SwiftUI").tag(0)
                Text("UICollectionView").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            // Content
            if selectedTab == 0 {
                SectionedSwiftUIContent(
                    viewModel: viewModel,
                    sections: sections,
                    selectedSectionIndex: $selectedSectionIndex,
                    selectedItemIndex: $selectedItemIndex
                )
            } else {
                SectionedUIKitContent(
                    viewModel: viewModel,
                    sections: sections
                )
            }
        }
        .navigationTitle("Sectioned List")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await sectionList.collect(viewModel.sections)
        }
    }
}

// MARK: - SwiftUI Content

private struct SectionedSwiftUIContent: View {
    let viewModel: SectionedListViewModel
    let sections: [ItemSectionWrapper]
    @Binding var selectedSectionIndex: Int?
    @Binding var selectedItemIndex: Int?

    var body: some View {
        VStack(spacing: 0) {
            List {
                ForEach(Array(sections.enumerated()), id: \.element.id) { sectionIndex, section in
                    Section {
                        ForEach(Array(section.items.enumerated()), id: \.element.id) { itemIndex, item in
                            SectionItemRow(
                                item: item,
                                isSelected: selectedSectionIndex == sectionIndex && selectedItemIndex == itemIndex,
                                onTap: {
                                    if selectedSectionIndex == sectionIndex && selectedItemIndex == itemIndex {
                                        selectedItemIndex = nil
                                    } else {
                                        selectedSectionIndex = sectionIndex
                                        selectedItemIndex = itemIndex
                                    }
                                }
                            )
                        }
                    } header: {
                        SectionHeaderRow(
                            header: section.header,
                            isSelected: selectedSectionIndex == sectionIndex,
                            onTap: {
                                if selectedSectionIndex == sectionIndex {
                                    selectedSectionIndex = nil
                                } else {
                                    selectedSectionIndex = sectionIndex
                                    selectedItemIndex = nil
                                }
                            }
                        )
                    }
                }
            }
            .listStyle(.plain)

            // Control buttons
            SectionedControlButtons(
                viewModel: viewModel,
                sections: sections,
                selectedSectionIndex: $selectedSectionIndex,
                selectedItemIndex: $selectedItemIndex
            )
        }
    }
}

// MARK: - Section Header Row

private struct SectionHeaderRow: View {
    let header: SectionHeaderWrapper
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Text(header.title)
            .font(.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
            .padding(.horizontal, 16)
            .background(header.color.opacity(isSelected ? 1.0 : 0.8))
            .textCase(nil) // Prevent automatic uppercasing of section headers
            .listRowInsets(EdgeInsets())
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
    }
}

// MARK: - Section Item Row

private struct SectionItemRow: View {
    let item: ItemWrapper
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.title)
                .font(.body)

            Text("ID: \(item.id.prefix(8))...")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.leading, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Color.blue.opacity(0.2) : Color.clear)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}

// MARK: - Control Buttons

private struct SectionedControlButtons: View {
    let viewModel: SectionedListViewModel
    let sections: [ItemSectionWrapper]
    @Binding var selectedSectionIndex: Int?
    @Binding var selectedItemIndex: Int?

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button("+ Section") {
                    viewModel.addSection()
                }
                .buttonStyle(.bordered)

                if selectedSectionIndex != nil {
                    Button("- Section") {
                        if let index = selectedSectionIndex {
                            viewModel.removeSection(index: Int32(index))
                            selectedSectionIndex = nil
                            selectedItemIndex = nil
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                }

                Button("Clear") {
                    viewModel.clearSections()
                    selectedSectionIndex = nil
                    selectedItemIndex = nil
                }
                .buttonStyle(.bordered)
            }

            HStack {
                if let sectionIndex = selectedSectionIndex {
                    Button("+ Item") {
                        viewModel.addItemToSection(sectionIndex: Int32(sectionIndex))
                    }
                    .buttonStyle(.bordered)

                    if let itemIndex = selectedItemIndex {
                        Button("- Item") {
                            viewModel.removeItemFromSection(sectionIndex: Int32(sectionIndex), itemIndex: Int32(itemIndex))
                            selectedItemIndex = nil
                        }
                        .buttonStyle(.bordered)
                        .tint(.red)
                    }

                    if sectionIndex > 0 {
                        Button("Move Up") {
                            viewModel.moveSection(fromIndex: Int32(sectionIndex), toIndex: Int32(sectionIndex - 1))
                            selectedSectionIndex = sectionIndex - 1
                        }
                        .buttonStyle(.bordered)
                    }

                    if sectionIndex < sections.count - 1 {
                        Button("Move Down") {
                            viewModel.moveSection(fromIndex: Int32(sectionIndex), toIndex: Int32(sectionIndex + 1))
                            selectedSectionIndex = sectionIndex + 1
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
        .padding()
        .background(Color(.systemBackground))
    }
}

// MARK: - UIKit Content

private struct SectionedUIKitContent: View {
    let viewModel: SectionedListViewModel
    let sections: [ItemSectionWrapper]
    @State private var selectedSectionIndex: Int = -1

    var body: some View {
        VStack(spacing: 0) {
            SectionedListViewControllerRepresentable(
                viewModel: viewModel,
                selectedSectionIndex: $selectedSectionIndex
            )

            // Section action buttons
            if selectedSectionIndex >= 0 && selectedSectionIndex < sections.count {
                HStack {
                    Button("Add Item") {
                        viewModel.addItemToSection(sectionIndex: Int32(selectedSectionIndex))
                    }
                    .buttonStyle(.bordered)

                    Button("Remove Section") {
                        viewModel.removeSection(index: Int32(selectedSectionIndex))
                        selectedSectionIndex = -1
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                }
                .padding()
            }
        }
    }
}

// MARK: - UIViewControllerRepresentable

private struct SectionedListViewControllerRepresentable: UIViewControllerRepresentable {
    let viewModel: SectionedListViewModel
    @Binding var selectedSectionIndex: Int

    func makeUIViewController(context: Context) -> SectionedListViewController {
        let vc = SectionedListViewController(viewModel: viewModel)
        vc.onSectionSelected = { index in
            selectedSectionIndex = index
        }
        return vc
    }

    func updateUIViewController(_ uiViewController: SectionedListViewController, context: Context) {
        uiViewController.selectedSectionIndex = selectedSectionIndex
    }
}

#Preview {
    NavigationStack {
        SectionedListView()
    }
}
