import Observation
import Foundation

@Observable
@MainActor
final class SharedRecipesViewModel {
    var recipes: [SharedRecipe] = []
    var isLoading = true
    var searchQuery = ""
    var errorMessage: String? = nil

    private let service: SharedRecipeService
    private let authRepository: AuthRepository

    var currentUid: String? { authRepository.uid }

    var filtered: [SharedRecipe] {
        guard !searchQuery.isEmpty else { return recipes }
        let q = searchQuery.lowercased()
        return recipes.filter {
            $0.title.lowercased().contains(q) ||
            $0.tags.contains { $0.lowercased().contains(q) } ||
            ($0.authorDisplayName?.lowercased().contains(q) ?? false)
        }
    }

    init(service: SharedRecipeService, authRepository: AuthRepository) {
        self.service = service
        self.authRepository = authRepository
    }

    func startListening() async {
        isLoading = true
        for await batch in service.sharedRecipesStream() {
            recipes = batch
            isLoading = false
        }
    }
}
