import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class ProfileViewModel {
    var recipes: [ProfileRecipeSummary] = []
    var isLoading = true
    /// True when the viewer is an accepted co-chef of this profile (sees friends + public).
    var isCoChef = false

    private let socialRepository: SocialRepository
    private let authorUid: String

    init(socialRepository: SocialRepository, authorUid: String) {
        self.socialRepository = socialRepository
        self.authorUid = authorUid
    }

    func load() async {
        // Co-chefs see the author's friends + public recipes; everyone else sees public only.
        // (The Firestore read rule enforces this — query the matching tier set.)
        let accepted = await socialRepository.getFollowStatus(targetUid: authorUid) == "accepted"
        let result = await socialRepository.getAuthorRecipes(authorUid: authorUid, includeFriendsOnly: accepted)
        isCoChef = accepted
        recipes = result
        isLoading = false
    }
}

// MARK: - View

struct ProfileView: View {
    let uid: String
    let name: String

    @Environment(AppContainer.self) private var container
    @State private var viewModel: ProfileViewModel?
    @State private var selected: ProfileRecipeSummary?

    var body: some View {
        Group {
            if let vm = viewModel {
                ProfileContent(viewModel: vm, name: name, selected: $selected)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $selected) { recipe in
            ReceivedRecipeView(source: .direct(recipeId: recipe.recipeId, authorUid: uid, authorName: name))
        }
        .onAppear {
            if viewModel == nil {
                viewModel = ProfileViewModel(socialRepository: container.socialRepository, authorUid: uid)
                Task { await viewModel?.load() }
            }
        }
    }
}

// MARK: - Content

private struct ProfileContent: View {
    @Bindable var viewModel: ProfileViewModel
    let name: String
    @Binding var selected: ProfileRecipeSummary?

    var body: some View {
        Group {
            if viewModel.isLoading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        // Header
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(Color.accentColor.opacity(0.15)).frame(width: 52, height: 52)
                                Text(String(name.prefix(1)).uppercased())
                                    .font(.title2).foregroundStyle(Color.accentColor)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(name).font(.headline)
                                Label(viewModel.isCoChef ? "Co-Chef" : "Chef",
                                      systemImage: viewModel.isCoChef ? "person.2.fill" : "person.fill")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 16).padding(.top, 12)

                        if viewModel.recipes.isEmpty {
                            ContentUnavailableView(
                                "No shared recipes",
                                systemImage: "tray",
                                description: Text(viewModel.isCoChef
                                    ? "\(name) hasn't shared any recipes with co-chefs yet."
                                    : "\(name) has no public recipes yet.")
                            )
                            .padding(.top, 40)
                        } else {
                            ForEach(viewModel.recipes) { recipe in
                                Button { selected = recipe } label: {
                                    ProfileRecipeCard(recipe: recipe)
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
}

private struct ProfileRecipeCard: View {
    let recipe: ProfileRecipeSummary

    private var timeLabel: String {
        var parts: [String] = []
        if let p = recipe.prepTimeMinutes { parts.append("Prep \(p)min") }
        if let c = recipe.cookTimeMinutes { parts.append("Cook \(c)min") }
        return parts.joined(separator: "  ·  ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(recipe.title).font(.title3).fontWeight(.semibold).lineLimit(2)
                Spacer()
                if recipe.visibility == "friends" {
                    Label("Co-Chefs", systemImage: "person.2")
                        .font(.caption2).foregroundStyle(.secondary)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color(.tertiarySystemFill)).clipShape(Capsule())
                }
            }
            if !timeLabel.isEmpty {
                Text(timeLabel).font(.caption).foregroundStyle(.secondary)
            }
            if !recipe.tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(recipe.tags, id: \.self) { tag in
                            Text(tag).font(.caption2)
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Color(.tertiarySystemFill)).clipShape(Capsule())
                        }
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
