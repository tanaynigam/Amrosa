import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class SharedInboxViewModel {
    /// Pending shares not yet saved — shown at the top as "In review".
    var pending: [ReceivedRecipeSummary] = []
    /// Saved received recipes (cached references) — normal cards.
    var saved: [RecipeModel] = []
    var isLoading = true
    private var streamTask: Task<Void, Never>? = nil

    private let socialRepository: SocialRepository
    private let repository: RecipeRepository

    var isEmpty: Bool { pending.isEmpty && saved.isEmpty }

    init(socialRepository: SocialRepository, repository: RecipeRepository) {
        self.socialRepository = socialRepository
        self.repository = repository
    }

    func start() {
        loadSaved()
        guard streamTask == nil else { return }
        streamTask = Task {
            for await batch in socialRepository.receivedRecipesSummaryStream() {
                guard !Task.isCancelled else { break }
                pending = batch
                isLoading = false
            }
        }
    }

    /// Saved received recipes come from local Room cache (refreshed by sync).
    func loadSaved() {
        saved = (try? repository.fetchReceivedRecipes()) ?? []
        isLoading = false
    }

    /// Dismiss a pending share (delete the pointer) — clears stale/unwanted shares.
    func dismissPending(_ shareId: String) {
        Task {
            await socialRepository.deleteReceivedPointer(shareId: shareId)
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
    }
}

// MARK: - View

struct SharedInboxView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: SharedInboxViewModel?
    @State private var selectedShareId: String? = nil
    @State private var selectedRecipe: RecipeModel? = nil

    var body: some View {
        Group {
            if let vm = viewModel {
                SharedInboxContent(
                    viewModel: vm,
                    selectedShareId: $selectedShareId,
                    selectedRecipe: $selectedRecipe
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Shared Recipes")
        .navigationDestination(item: $selectedShareId) { shareId in
            ReceivedRecipeView(shareId: shareId)
        }
        .navigationDestination(item: $selectedRecipe) { recipe in
            RecipeDetailView(recipe: recipe)
        }
        .onAppear {
            if viewModel == nil {
                viewModel = SharedInboxViewModel(
                    socialRepository: container.socialRepository,
                    repository: container.recipeRepository
                )
            }
            viewModel?.start()
            viewModel?.loadSaved()   // refresh saved list when returning to the tab
        }
        .onDisappear {
            viewModel?.stop()
        }
    }
}

// MARK: - Content

private struct SharedInboxContent: View {
    @Bindable var viewModel: SharedInboxViewModel
    @Binding var selectedShareId: String?
    @Binding var selectedRecipe: RecipeModel?

    var body: some View {
        Group {
            if viewModel.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "tray")
                        .font(.system(size: 48))
                        .foregroundStyle(.secondary)
                    Text("No recipes shared with you yet")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                    Text("When a co-chef sends you a recipe, it will appear here.")
                        .font(.subheadline)
                        .foregroundStyle(.tertiary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        // Pending shares — "In review", tap to review & save
                        ForEach(viewModel.pending) { item in
                            Button {
                                selectedShareId = item.shareId
                            } label: {
                                SharedRecipeCard(item: item, inReview: true,
                                                 onDismiss: { viewModel.dismissPending(item.shareId) })
                            }
                            .buttonStyle(.plain)
                        }
                        // Saved received recipes — open the normal (read-only) detail
                        ForEach(viewModel.saved) { recipe in
                            Button {
                                selectedRecipe = recipe
                            } label: {
                                SavedReceivedCard(recipe: recipe)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
    }
}

// MARK: - Pending share card (In review)

struct SharedRecipeCard: View {
    let item: ReceivedRecipeSummary
    var inReview: Bool = false
    var onDismiss: (() -> Void)? = nil

    private var timeLabel: String {
        var parts: [String] = []
        if let p = item.prepTimeMinutes { parts.append("Prep \(p)min") }
        if let c = item.cookTimeMinutes { parts.append("Cook \(c)min") }
        return parts.joined(separator: "  ·  ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if inReview {
                HStack {
                    Text("In review — tap to save")
                        .font(.caption2).fontWeight(.medium)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color(.tertiarySystemFill))
                        .clipShape(Capsule())
                    Spacer()
                    if let onDismiss = onDismiss {
                        Button { onDismiss() } label: {
                            Image(systemName: "xmark").font(.caption).foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Text(item.title)
                .font(.title3).fontWeight(.semibold)
                .lineLimit(2)
                .foregroundStyle(.primary)

            if !timeLabel.isEmpty {
                Text(timeLabel).font(.caption).foregroundStyle(.secondary)
            }

            if !item.tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(item.tags, id: \.self) { tag in
                            Text(tag).font(.caption2)
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Color(.tertiarySystemFill))
                                .clipShape(Capsule())
                        }
                    }
                }
            }

            // Original author + "from sender"
            HStack(spacing: 4) {
                Image(systemName: "person").font(.caption2).foregroundStyle(.secondary)
                Text(item.authorDisplayName)
                    .font(.caption).fontWeight(.medium).foregroundStyle(.secondary)
                if item.fromDisplayName != item.authorDisplayName {
                    Text("· from \(item.fromDisplayName)").font(.caption).foregroundStyle(.tertiary)
                }
                Spacer()
                Text(item.sharedAt.relativeString()).font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

// MARK: - Saved received recipe card (read-only)

private struct SavedReceivedCard: View {
    let recipe: RecipeModel

    private var timeLabel: String {
        var parts: [String] = []
        if let p = recipe.prepTimeMinutes { parts.append("Prep \(p)min") }
        if let c = recipe.cookTimeMinutes { parts.append("Cook \(c)min") }
        return parts.joined(separator: "  ·  ")
    }

    /// v2 author label — Tab 2 is always someone else's recipe: "B" / "Imported by B".
    private var authorLabel: String {
        let name = recipe.authorDisplayName ?? "Unknown"
        return recipe.isImported ? "Imported by \(name)" : name
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(recipe.title)
                .font(.title3).fontWeight(.semibold)
                .lineLimit(2)
                .foregroundStyle(.primary)

            if !timeLabel.isEmpty {
                Text(timeLabel).font(.caption).foregroundStyle(.secondary)
            }

            if !recipe.tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(recipe.tags, id: \.self) { tag in
                            Text(tag).font(.caption2)
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Color(.tertiarySystemFill))
                                .clipShape(Capsule())
                        }
                    }
                }
            }

            HStack(spacing: 4) {
                Image(systemName: "person").font(.caption2).foregroundStyle(.secondary)
                Text(authorLabel)
                    .font(.caption).fontWeight(.medium).foregroundStyle(.secondary)
                Spacer()
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
