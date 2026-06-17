import SwiftUI

struct YourRecipesView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: YourRecipesViewModel?
    @State private var selectedRecipe: RecipeModel? = nil
    @State private var showAddMenu = false
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
        .navigationTitle("My Recipes")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showAddMenu = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showAddMenu) {
            AddRecipeSheet(
                isPresented: $showAddMenu,
                onTypeItOut: { showAddMenu = false; navigateToFreeform = true },
                onImport: { showAddMenu = false; navigateToImport = true }
            )
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
                viewModel = YourRecipesViewModel(
                    repository: container.recipeRepository,
                    sharedRecipeService: container.sharedRecipeService,
                    authRepository: container.authRepository
                )
            }
            viewModel?.load()
        }
    }
}

// MARK: - Add recipe bottom sheet (matches Android ModalBottomSheet)

private struct AddRecipeSheet: View {
    @Binding var isPresented: Bool
    let onTypeItOut: () -> Void
    let onImport: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Color(.systemFill))
                .frame(width: 36, height: 4)
                .padding(.top, 12)

            Text("Add Recipe")
                .font(.headline)
                .padding(.vertical, 16)

            Divider()

            Button(action: onTypeItOut) {
                HStack(spacing: 14) {
                    Image(systemName: "text.cursor")
                        .font(.title3)
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Type it out")
                            .font(.body).fontWeight(.medium).foregroundStyle(.primary)
                        Text("Write a recipe and format it with Gemini")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 20).padding(.vertical, 14)
                .background(Color.accentColor.opacity(0.07))
            }
            .buttonStyle(.plain)

            Divider().padding(.leading, 66)

            Button(action: onImport) {
                HStack(spacing: 14) {
                    Image(systemName: "link")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Import from URL or file")
                            .font(.body).foregroundStyle(.primary)
                        Text("Import from a website, PDF, or spreadsheet")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 20).padding(.vertical, 14)
            }
            .buttonStyle(.plain)

            Spacer().frame(minHeight: 20)
        }
        .presentationDetents([.height(230)])
        .presentationDragIndicator(.hidden)
    }
}

private struct YourRecipesContent: View {
    @Bindable var viewModel: YourRecipesViewModel
    @Binding var selectedRecipe: RecipeModel?
    var onTapNeedsReview: (RecipeModel) -> Void

    var body: some View {
        VStack(spacing: 0) {
            // ── Compact search bar (sticky) ──────────────────────────────
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                TextField("Search recipes…", text: $viewModel.searchText)
                if !viewModel.searchText.isEmpty {
                    Button { viewModel.searchText = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.subheadline).foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(Color(.secondarySystemBackground))
            .clipShape(Capsule())
            .padding(.horizontal, 16)
            .padding(.vertical, 6)

            Divider()

            // ── Scrolling content (chips scroll away with cards) ──────────
            ScrollView {
                LazyVStack(spacing: 12) {
                    // Filter chips as the first scrollable item
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(YourRecipesViewModel.RecipeFilter.allCases, id: \.self) { filter in
                                FilterChip(label: filter.rawValue, isSelected: viewModel.filterMode == filter) {
                                    viewModel.filterMode = filter
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                    .padding(.top, 8)

                    if viewModel.filteredRecipes.isEmpty {
                        ContentUnavailableView(
                            "No Recipes Yet",
                            systemImage: "bookmark",
                            description: Text("Tap + to add your first recipe.")
                        )
                        .padding(.top, 40)
                    } else {
                        ForEach(viewModel.filteredRecipes) { recipe in
                            Button {
                                if recipe.needsReview { onTapNeedsReview(recipe) }
                                else { selectedRecipe = recipe }
                            } label: {
                                RecipeCard(recipe: recipe, showNeedsReviewBanner: true,
                                           likeCount: viewModel.likeCounts[recipe.id] ?? 0)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 16)
                        }
                    }
                }
                .padding(.bottom, 24)
            }
        }
    }
}
