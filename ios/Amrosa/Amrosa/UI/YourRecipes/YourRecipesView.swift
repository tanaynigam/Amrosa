import SwiftUI

struct YourRecipesView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: YourRecipesViewModel?
    @State private var selectedRecipe: RecipeModel? = nil
    @State private var showAddSheet = false
    @State private var navigateToImport = false
    @State private var navigateToFreeform = false
    @State private var navigateToImportForReview: RecipeModel? = nil

    var body: some View {
        Group {
            if let vm = viewModel {
                YourRecipesContent(
                    viewModel: vm,
                    selectedRecipe: $selectedRecipe,
                    onTapNeedsReview: { recipe in
                        navigateToImportForReview = recipe
                    }
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Your Recipes")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showAddSheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .confirmationDialog("Add Recipe", isPresented: $showAddSheet) {
            Button("Type it out") { navigateToFreeform = true }
            Button("Import from URL or file") { navigateToImport = true }
            Button("Cancel", role: .cancel) {}
        }
        .navigationDestination(item: $selectedRecipe) { recipe in
            RecipeDetailView(recipe: recipe)
        }
        .navigationDestination(isPresented: $navigateToImport) {
            ImportView()
        }
        .navigationDestination(isPresented: $navigateToFreeform) {
            FreeformEntryView()
        }
        .navigationDestination(item: $navigateToImportForReview) { recipe in
            ImportView(reviewRecipeId: recipe.id)
        }
        .onAppear {
            if viewModel == nil {
                viewModel = YourRecipesViewModel(repository: container.recipeRepository)
            }
            viewModel?.load()
        }
    }
}

private struct YourRecipesContent: View {
    @Bindable var viewModel: YourRecipesViewModel
    @Binding var selectedRecipe: RecipeModel?
    var onTapNeedsReview: (RecipeModel) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    TextField("Search your recipes…", text: $viewModel.searchText)
                }
                .padding(10)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal)
                .padding(.vertical, 8)

                // Filter chips
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(YourRecipesViewModel.RecipeFilter.allCases, id: \.self) { filter in
                            FilterChip(label: filter.rawValue, isSelected: viewModel.filterMode == filter) {
                                viewModel.filterMode = filter
                            }
                        }
                    }
                    .padding(.horizontal)
                }
                .padding(.bottom, 8)

                if viewModel.filteredRecipes.isEmpty {
                    ContentUnavailableView(
                        "No Recipes Yet",
                        systemImage: "bookmark",
                        description: Text("Tap + to add your first recipe.")
                    )
                    .padding(.top, 60)
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.filteredRecipes) { recipe in
                            Button {
                                if recipe.needsReview {
                                    onTapNeedsReview(recipe)
                                } else {
                                    selectedRecipe = recipe
                                }
                            } label: {
                                RecipeCard(recipe: recipe, showNeedsReviewBanner: true)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 24)
                }
            }
        }
    }
}
