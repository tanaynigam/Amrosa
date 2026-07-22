import SwiftData
import Observation
import Foundation

@Observable
@MainActor
final class AllRecipesViewModel {
    var recipes: [RecipeModel] = []
    var searchText: String = ""
    var selectedTag: String? = nil
    var errorMessage: String? = nil

    private let repository: RecipeRepository

    init(repository: RecipeRepository) {
        self.repository = repository
    }

    var filteredRecipes: [RecipeModel] {
        var result = recipes
        if !searchText.isEmpty {
            let q = searchText.lowercased()
            result = result.filter {
                $0.title.lowercased().contains(q) ||
                ($0.recipeDescription?.lowercased().contains(q) ?? false) ||
                $0.tags.contains(where: { $0.lowercased().contains(q) })
            }
        }
        if let tag = selectedTag {
            result = result.filter { $0.tags.contains(tag) }
        }
        return result
    }

    var allTags: [String] {
        Array(Set(recipes.flatMap { $0.tags })).sorted()
    }

    func load() {
        do {
            recipes = try repository.fetchAllRecipes()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
