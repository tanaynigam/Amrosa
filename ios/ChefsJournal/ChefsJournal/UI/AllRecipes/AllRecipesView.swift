import SwiftUI

struct AllRecipesView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: AllRecipesViewModel?
    @State private var selectedRecipe: RecipeModel? = nil

    var body: some View {
        Group {
            if let vm = viewModel {
                AllRecipesContent(viewModel: vm, selectedRecipe: $selectedRecipe)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Amrita & Ambrosia")
        .navigationDestination(item: $selectedRecipe) { recipe in
            RecipeDetailView(recipe: recipe)
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(destination: AccountView()) {
                    Image(systemName: "person.circle")
                }
            }
        }
        .onAppear {
            if viewModel == nil {
                viewModel = AllRecipesViewModel(repository: container.recipeRepository)
            }
            viewModel?.load()
        }
    }
}

private struct AllRecipesContent: View {
    @Bindable var viewModel: AllRecipesViewModel
    @Binding var selectedRecipe: RecipeModel?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    TextField("Search recipes…", text: $viewModel.searchText)
                }
                .padding(10)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal)
                .padding(.vertical, 8)

                // Tag filter chips
                if !viewModel.allTags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            FilterChip(label: "All", isSelected: viewModel.selectedTag == nil) {
                                viewModel.selectedTag = nil
                            }
                            ForEach(viewModel.allTags, id: \.self) { tag in
                                FilterChip(label: tag, isSelected: viewModel.selectedTag == tag) {
                                    viewModel.selectedTag = viewModel.selectedTag == tag ? nil : tag
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                    .padding(.bottom, 8)
                }

                // Recipe list
                LazyVStack(spacing: 12) {
                    ForEach(viewModel.filteredRecipes) { recipe in
                        Button {
                            selectedRecipe = recipe
                        } label: {
                            RecipeCard(recipe: recipe)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 24)

                if viewModel.filteredRecipes.isEmpty {
                    ContentUnavailableView(
                        viewModel.searchText.isEmpty ? "No Recipes" : "No Results",
                        systemImage: "fork.knife",
                        description: Text(viewModel.searchText.isEmpty
                            ? "Recipes will appear here once synced."
                            : "Try a different search term.")
                    )
                    .padding(.top, 60)
                }
            }
        }
    }
}

struct FilterChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline)
                .fontWeight(isSelected ? .semibold : .regular)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(isSelected ? Color.accentColor : Color(.secondarySystemBackground))
                .foregroundStyle(isSelected ? .white : .primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
