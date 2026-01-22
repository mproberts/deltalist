import SwiftUI

/// Sectioned list demo screen with headers and items.
struct SectionedListView: View {
    @StateObject private var viewModel = SectionedListViewModelAdapter()
    @State private var selectedTab = 0
    @State private var selectedSectionIndex: Int? = nil
    @State private var selectedItemIndex: Int? = nil

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
                    selectedSectionIndex: $selectedSectionIndex,
                    selectedItemIndex: $selectedItemIndex
                )
            } else {
                SectionedUIKitContent(viewModel: viewModel)
            }
        }
        .navigationTitle("Sectioned List")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - SwiftUI Content

private struct SectionedSwiftUIContent: View {
    @ObservedObject var viewModel: SectionedListViewModelAdapter
    @Binding var selectedSectionIndex: Int?
    @Binding var selectedItemIndex: Int?

    var body: some View {
        VStack(spacing: 0) {
            List {
                ForEach(Array(viewModel.sections.enumerated()), id: \.element.id) { sectionIndex, section in
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
    @ObservedObject var viewModel: SectionedListViewModelAdapter
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
                            viewModel.removeSection(at: index)
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
                        viewModel.addItemToSection(sectionIndex)
                    }
                    .buttonStyle(.bordered)

                    if let itemIndex = selectedItemIndex {
                        Button("- Item") {
                            viewModel.removeItemFromSection(sectionIndex, itemIndex: itemIndex)
                            selectedItemIndex = nil
                        }
                        .buttonStyle(.bordered)
                        .tint(.red)
                    }

                    if sectionIndex > 0 {
                        Button("Move Up") {
                            viewModel.moveSection(from: sectionIndex, to: sectionIndex - 1)
                            selectedSectionIndex = sectionIndex - 1
                        }
                        .buttonStyle(.bordered)
                    }

                    if sectionIndex < viewModel.sections.count - 1 {
                        Button("Move Down") {
                            viewModel.moveSection(from: sectionIndex, to: sectionIndex + 1)
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
    @ObservedObject var viewModel: SectionedListViewModelAdapter
    @State private var selectedSectionIndex: Int = -1

    var body: some View {
        VStack(spacing: 0) {
            SectionedListViewControllerRepresentable(
                viewModel: viewModel,
                selectedSectionIndex: $selectedSectionIndex
            )

            // Section action buttons
            if selectedSectionIndex >= 0 && selectedSectionIndex < viewModel.sections.count {
                HStack {
                    Button("Add Item") {
                        viewModel.addItemToSection(selectedSectionIndex)
                    }
                    .buttonStyle(.bordered)

                    Button("Remove Section") {
                        viewModel.removeSection(at: selectedSectionIndex)
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
    let viewModel: SectionedListViewModelAdapter
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
