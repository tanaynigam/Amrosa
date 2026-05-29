import SwiftData
import Observation
import Foundation

@Observable
@MainActor
final class YourRecipesViewModel {
    var recipes: [RecipeModel] = []
    var sharedRecipes: [ReceivedRecipeSummary] = []
    var searchText: String = ""
    var filterMode: RecipeFilter = .all
    var errorMessage: String? = nil

    private let repository: RecipeRepository
    private let socialRepository: SocialRepository
    private var sharedTask: Task<Void, Never>? = nil

    init(repository: RecipeRepository, socialRepository: SocialRepository) {
        self.repository = repository
        self.socialRepository = socialRepository
    }

    enum RecipeFilter: String, CaseIterable {
        case all = "All"
        case personal = "Personal"
        case imported = "Imported"
        case shared = "Shared"
    }

    var isSharedMode: Bool { filterMode == .shared }

    var filteredRecipes: [RecipeModel] {
        if filterMode == .shared { return [] }
        var result = recipes

        switch filterMode {
        case .personal: result = result.filter { !$0.isImported }
        case .imported: result = result.filter { $0.isImported }
        default: break
        }

        if !searchText.isEmpty {
            let q = searchText.lowercased()
            result = result.filter {
                $0.title.lowercased().contains(q) ||
                ($0.recipeDescription?.lowercased().contains(q) ?? false)
            }
        }

        // needsReview recipes appear first
        return result.sorted { lhs, rhs in
            if lhs.needsReview != rhs.needsReview { return lhs.needsReview }
            return lhs.updatedAt > rhs.updatedAt
        }
    }

    var filteredSharedRecipes: [ReceivedRecipeSummary] {
        guard filterMode == .shared else { return [] }
        if searchText.isEmpty { return sharedRecipes }
        let q = searchText.lowercased()
        return sharedRecipes.filter { r in
            r.title.lowercased().contains(q) ||
            r.tags.contains { $0.lowercased().contains(q) }
        }
    }

    var pendingReviewCount: Int {
        recipes.filter { $0.needsReview }.count
    }

    func load() {
        do {
            recipes = try repository.fetchAllRecipes()
        } catch {
            errorMessage = error.localizedDescription
        }
        startSharedStream()
    }

    private func startSharedStream() {
        guard sharedTask == nil else { return }
        sharedTask = Task {
            for await batch in socialRepository.receivedRecipesSummaryStream() {
                guard !Task.isCancelled else { break }
                sharedRecipes = batch
            }
        }
    }

    func stop() {
        sharedTask?.cancel()
        sharedTask = nil
    }
}
