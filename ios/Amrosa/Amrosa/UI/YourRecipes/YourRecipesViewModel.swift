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

    private let repository: RecipeRepository

    init(repository: RecipeRepository) {
        self.repository = repository
    }

    enum RecipeFilter: String, CaseIterable {
        case all = "All"
        case personal = "Personal"
        case imported = "Imported"
    }

    var filteredRecipes: [RecipeModel] {
        var result = recipes

        switch filterMode {
        case .personal:
            result = result.filter { !$0.isImported }
        case .imported:
            result = result.filter { $0.isImported }
        case .all:
            break
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
            // Show all recipes that belong to the user:
            // - seeded/personal (authorId nil = pre-auth seeded, counts as yours)
            // - any recipe the user created or imported
            // Exclude only recipes from other users' shared copies
            // (authorId != nil && authorId != currentUid would exclude those,
            // but since we never store other users' shared recipes in local SwiftData
            // except as copies with currentUid, showing all local recipes is correct)
            recipes = try repository.fetchAllRecipes()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
