import SwiftUI

struct SharedRecipesView: View {
    @Environment(AppContainer.self) private var container
    @Binding var deepLinkRecipeId: String?
    @State private var viewModel: SharedRecipesViewModel?
    @State private var selectedSharedId: String? = nil

    var body: some View {
        Group {
            if let vm = viewModel {
                SharedRecipesContent(
                    viewModel: vm,
                    deepLinkRecipeId: $deepLinkRecipeId,
                    selectedSharedId: $selectedSharedId
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Shared")
        .onAppear {
            if viewModel == nil {
                viewModel = SharedRecipesViewModel(
                    service: container.sharedRecipeService,
                    authRepository: container.authRepository
                )
                Task { await viewModel?.startListening() }
            }
        }
        .onChange(of: deepLinkRecipeId) { _, newId in
            if newId != nil { selectedSharedId = newId }
        }
    }
}

// MARK: - Content

private struct SharedRecipesContent: View {
    @Bindable var viewModel: SharedRecipesViewModel
    @Binding var deepLinkRecipeId: String?
    @Binding var selectedSharedId: String?

    var body: some View {
        VStack(spacing: 0) {
            // Search bar
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search shared recipes…", text: $viewModel.searchQuery)
                    .autocapitalization(.none)
                if !viewModel.searchQuery.isEmpty {
                    Button { viewModel.searchQuery = "" } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                }
            }
            .padding(10)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal)
            .padding(.vertical, 8)

            if viewModel.isLoading {
                Spacer()
                ProgressView()
                Spacer()
            } else if viewModel.filtered.isEmpty {
                Spacer()
                VStack(spacing: 12) {
                    Image(systemName: "globe")
                        .font(.system(size: 48))
                        .foregroundStyle(.secondary)
                    Text(viewModel.searchQuery.isEmpty ? "No shared recipes yet" : "No matching recipes")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                    if viewModel.searchQuery.isEmpty {
                        Text("Make one of your recipes public to share it here.")
                            .font(.subheadline)
                            .foregroundStyle(.tertiary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                }
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(viewModel.filtered) { recipe in
                            SharedRecipeCard(
                                recipe: recipe,
                                isOwn: viewModel.currentUid != nil && recipe.authorId == viewModel.currentUid
                            ) {
                                selectedSharedId = recipe.id
                            }
                            .padding(.horizontal)
                        }
                    }
                    .padding(.vertical, 8)
                }
            }
        }
        .navigationDestination(item: $selectedSharedId) { recipeId in
            SharedRecipeDetailView(recipeId: recipeId)
                .onDisappear { deepLinkRecipeId = nil }
        }
    }
}

// MARK: - Shared recipe card

private struct SharedRecipeCard: View {
    let recipe: SharedRecipe
    let isOwn: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    Text(recipe.title)
                        .font(.headline)
                        .multilineTextAlignment(.leading)
                    Spacer()
                    if isOwn {
                        Text("Yours")
                            .font(.caption2)
                            .fontWeight(.semibold)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.accentColor.opacity(0.15))
                            .foregroundStyle(Color.accentColor)
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                }

                if let author = recipe.authorDisplayName {
                    Label(author, systemImage: "person")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                // Time chips
                let times = [
                    recipe.prepTimeMinutes.map { "Prep \($0.timeDisplayString)" },
                    recipe.cookTimeMinutes.map { "Cook \($0.timeDisplayString)" }
                ].compactMap { $0 }
                if !times.isEmpty {
                    HStack(spacing: 12) {
                        ForEach(times, id: \.self) { t in
                            Text(t).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }

                // Tags
                if !recipe.tags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(recipe.tags.prefix(5), id: \.self) { tag in
                                Text(tag)
                                    .font(.caption2)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Color(.tertiarySystemBackground))
                                    .clipShape(Capsule())
                            }
                        }
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}
