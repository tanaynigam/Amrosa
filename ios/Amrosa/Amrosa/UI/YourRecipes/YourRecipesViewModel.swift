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
    }
}
