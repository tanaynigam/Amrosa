import SwiftUI

// MARK: - Shelf

struct DiscoverShelf: Identifiable {
    let id = UUID()
    let title: String
    let recipes: [DiscoverRecipe]
}

// MARK: - ViewModel

@Observable
@MainActor
final class DiscoverViewModel {
    var isLoading = true
    var isRefreshing = false
    var leadMeal: MealType = .dinner
    var shelves: [DiscoverShelf] = []
    /// Ranked, meal-appropriate pool used by "Surprise me".
    var surprisePool: [DiscoverRecipe] = []

    private let repository: RecipeRepository
    private let socialRepository: SocialRepository
    private let sharedRecipeService: SharedRecipeService
    private let authRepository: AuthRepository

    private static let shelfLimit = 12
    private static let friendCap = 10

    init(repository: RecipeRepository, socialRepository: SocialRepository,
         sharedRecipeService: SharedRecipeService, authRepository: AuthRepository) {
        self.repository = repository
        self.socialRepository = socialRepository
        self.sharedRecipeService = sharedRecipeService
        self.authRepository = authRepository
    }

    func load(isRefresh: Bool = false) async {
        if isRefresh { isRefreshing = true } else { isLoading = true }
        let now = Date()
        let meal = MealClassifier.currentMeal(hour: Calendar.current.component(.hour, from: now))
        let myUid = authRepository.uid

        // ── Local recipes (your kitchen) — base recipes I own ──
        let local = (try? repository.fetchMyRecipes()) ?? []
        let ownTags = local.flatMap { $0.tags }
        let topCuisines = DiscoverRanker.topCuisines(ownTags)   // implicit affinity (explicit prefs = Phase 7)
        let cookedAt = (try? repository.cookedLog()) ?? [:]
        let localIds = Set(local.map { $0.id })

        let ownCandidates = local.map { r in
            DiscoverRecipe(recipeId: r.id, title: r.title, tags: r.tags,
                           prepTimeMinutes: r.prepTimeMinutes, cookTimeMinutes: r.cookTimeMinutes,
                           source: .own, authorUid: r.authorId, authorName: r.authorDisplayName, isLocal: true)
        }

        // ── Co-chefs' recipes ──
        let friends = await socialRepository.getFriendsOnce().prefix(Self.friendCap)
        var friendCandidates: [DiscoverRecipe] = []
        for friend in friends {
            let recipes = await socialRepository.getAuthorRecipes(authorUid: friend.uid, includeFriendsOnly: true)
            for s in recipes where !localIds.contains(s.recipeId) {
                friendCandidates.append(DiscoverRecipe(
                    recipeId: s.recipeId, title: s.title, tags: s.tags,
                    prepTimeMinutes: s.prepTimeMinutes, cookTimeMinutes: s.cookTimeMinutes,
                    source: .friend, authorUid: friend.uid, authorName: friend.displayName, isLocal: false))
            }
        }
        friendCandidates = dedupe(friendCandidates)

        // ── Public recipes (recent), excluding mine + dupes ──
        let friendIds = Set(friendCandidates.map { $0.recipeId })
        let recentPublic = await sharedRecipeService.getPublicRecipeSummaries()
        let publicCandidates = dedupe(recentPublic).filter {
            $0.authorUid != myUid && !localIds.contains($0.recipeId) && !friendIds.contains($0.recipeId)
        }

        func ranked(_ list: [DiscoverRecipe]) -> [DiscoverRecipe] {
            DiscoverRanker.rank(list, currentMeal: meal, topCuisines: topCuisines, cookedAt: cookedAt, now: now)
        }

        let all = ownCandidates + friendCandidates + publicCandidates
        let leadPool = ranked(all).filter {
            let m = MealClassifier.mealsFor($0.tags); return m.isEmpty || m.contains(meal)
        }

        let recentlyCooked = local
            .filter { cookedAt[$0.id] != nil }
            .sorted { (cookedAt[$0.id] ?? .distantPast) > (cookedAt[$1.id] ?? .distantPast) }
            .map { r in
                DiscoverRecipe(recipeId: r.id, title: r.title, tags: r.tags,
                               prepTimeMinutes: r.prepTimeMinutes, cookTimeMinutes: r.cookTimeMinutes,
                               source: .own, authorUid: r.authorId, authorName: r.authorDisplayName, isLocal: true)
            }

        let built: [DiscoverShelf] = [
            DiscoverShelf(title: "\(meal.label) ideas", recipes: Array(leadPool.prefix(Self.shelfLimit))),
            DiscoverShelf(title: "From your kitchen", recipes: Array(ranked(ownCandidates).prefix(Self.shelfLimit))),
            DiscoverShelf(title: "From your co-chefs", recipes: Array(ranked(friendCandidates).prefix(Self.shelfLimit))),
            DiscoverShelf(title: "Fresh from the community", recipes: Array(publicCandidates.prefix(Self.shelfLimit))),
            DiscoverShelf(title: "Recently cooked", recipes: Array(recentlyCooked.prefix(Self.shelfLimit)))
        ].filter { !$0.recipes.isEmpty }

        leadMeal = meal
        shelves = built
        surprisePool = leadPool.isEmpty ? all : leadPool
        isLoading = false
        isRefreshing = false
    }

    func surprise() -> DiscoverRecipe? { surprisePool.randomElement() }

    private func dedupe(_ list: [DiscoverRecipe]) -> [DiscoverRecipe] {
        var seen = Set<String>()
        return list.filter { seen.insert($0.recipeId).inserted }
    }
}

// MARK: - View

struct DiscoverView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: DiscoverViewModel?
    @State private var openLocal: RecipeModel?
    @State private var openRemote: DiscoverRecipe?

    var body: some View {
        Group {
            if let vm = viewModel {
                content(vm)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Discover")
        .navigationDestination(item: $openLocal) { recipe in
            RecipeDetailView(recipe: recipe)
        }
        .navigationDestination(item: $openRemote) { r in
            ReceivedRecipeView(source: .direct(recipeId: r.recipeId,
                                               authorUid: r.authorUid ?? "",
                                               authorName: r.authorName ?? "Someone"))
        }
        .onAppear {
            if viewModel == nil {
                viewModel = DiscoverViewModel(
                    repository: container.recipeRepository,
                    socialRepository: container.socialRepository,
                    sharedRecipeService: container.sharedRecipeService,
                    authRepository: container.authRepository
                )
                Task { await viewModel?.load() }
            }
        }
    }

    @ViewBuilder
    private func content(_ vm: DiscoverViewModel) -> some View {
        if vm.isLoading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if vm.shelves.isEmpty {
            ContentUnavailableView(
                "Nothing to discover yet",
                systemImage: "sparkles",
                description: Text("Add a few recipes or follow some co-chefs and ideas will show up here.")
            )
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 24) {
                    ForEach(vm.shelves) { shelf in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(shelf.title)
                                .font(.title3).fontWeight(.semibold)
                                .padding(.horizontal, 16)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 12) {
                                    ForEach(shelf.recipes) { recipe in
                                        Button { open(recipe) } label: {
                                            DiscoverCard(recipe: recipe)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 16)
                            }
                        }
                    }
                }
                .padding(.vertical, 12)
            }
            .refreshable { await vm.load(isRefresh: true) }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        if let pick = vm.surprise() { open(pick) }
                    } label: {
                        Label("Surprise me", systemImage: "dice")
                    }
                }
            }
        }
    }

    private func open(_ recipe: DiscoverRecipe) {
        if recipe.isLocal, let model = try? container.recipeRepository.fetchRecipe(id: recipe.recipeId) {
            openLocal = model
        } else {
            openRemote = recipe
        }
    }
}

// MARK: - Card

private struct DiscoverCard: View {
    let recipe: DiscoverRecipe

    private var timeLabel: String {
        var parts: [String] = []
        if let p = recipe.prepTimeMinutes { parts.append("Prep \(p)m") }
        if let c = recipe.cookTimeMinutes { parts.append("Cook \(c)m") }
        return parts.joined(separator: " · ")
    }
    private var sourceLabel: String? {
        switch recipe.source {
        case .own: return nil
        case .friend: return recipe.authorName
        case .public: return recipe.authorName ?? "Community"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(recipe.title)
                .font(.headline)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            if !timeLabel.isEmpty {
                Text(timeLabel).font(.caption2).foregroundStyle(.secondary)
            }
            if !recipe.tags.isEmpty {
                Text(recipe.tags.prefix(2).joined(separator: " · "))
                    .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if let src = sourceLabel {
                Label(src, systemImage: recipe.source == .friend ? "person.2" : "globe")
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(12)
        .frame(width: 170, height: 130, alignment: .topLeading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}
