import SwiftData
import Observation
import Foundation

@Observable
@MainActor
final class YourRecipesViewModel {
    var recipes: [RecipeModel] = []
    var searchText: String = ""
    var filterMode: RecipeFilter = .all
    var errorMessage: String? = nil
    /// F20: like counts for the user's own published recipes (recipeId → count), shown on cards.
    var likeCounts: [String: Int] = [:]

    private let repository: RecipeRepository
    private let sharedRecipeService: SharedRecipeService?
    private let authRepository: AuthRepository?

    init(repository: RecipeRepository, sharedRecipeService: SharedRecipeService? = nil, authRepository: AuthRepository? = nil) {
        self.repository = repository
        self.sharedRecipeService = sharedRecipeService
        self.authRepository = authRepository
    }

    enum RecipeFilter: String, CaseIterable {
        case all = "All"
        case personal = "Personal"
        case imported = "Imported"
    }

    var filteredRecipes: [RecipeModel] {
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

    var pendingReviewCount: Int {
        recipes.filter { $0.needsReview }.count
    }

    func load() {
        do {
            // Tab 1 — my recipes only (excludes received references, which live in Tab 2)
            recipes = try repository.fetchMyRecipes()
        } catch {
            errorMessage = error.localizedDescription
        }
        // F20: pull the user's own published like counts (one query) for the cards.
        if let uid = authRepository?.uid, let svc = sharedRecipeService {
            Task { likeCounts = await svc.getAuthorLikeCounts(uid) }
        }
    }
}
