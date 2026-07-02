import SwiftData
import Observation
import Foundation

@Observable
@MainActor
final class ImportViewModel {
    var urlText: String = ""
    var isImporting = false
    var isImportingFile = false
    var isReimporting = false
    var errorMessage: String? = nil
    var parsedRecipe: ParsedRecipeData? = nil
    var reviewingRecipeId: String? = nil
    var isOwnRecipe = false
    var parseNotesDismissed = false

    private let repository: RecipeRepository
    private let cloudFunctions: CloudFunctionsService
    private let authRepository: AuthRepository

    // Set externally to pre-load a pending-review recipe
    var preloadRecipeId: String? = nil

    init(repository: RecipeRepository, cloudFunctions: CloudFunctionsService, authRepository: AuthRepository) {
        self.repository = repository
        self.cloudFunctions = cloudFunctions
        self.authRepository = authRepository
    }

    func loadPendingReview(recipeId: String) {
        guard let recipe = try? repository.fetchRecipe(id: recipeId) else { return }
        reviewingRecipeId = recipeId
        parsedRecipe = recipeToParsed(recipe)
    }

    // MARK: - URL import

    func importFromURL() {
        let url = urlText.trimmed
        guard !url.isEmpty else {
            errorMessage = "Please enter a URL."
            return
        }
        isImporting = true
        errorMessage = nil

        Task {
            defer { isImporting = false }
            do {
                let parsed = try await cloudFunctions.parseRecipeUrl(url)
                let recipeId = UUID().uuidString
                let authorId = authRepository.uid
                let authorName = isOwnRecipe ? (authRepository.displayName ?? "Me") : "Imported"
                _ = try repository.insertFullRecipeFromParsed(
                    recipeId: recipeId,
                    parsed: parsed,
                    authorId: authorId,
                    authorDisplayName: authorName,
                    isImported: !isOwnRecipe,
                    needsReview: true
                )
                reviewingRecipeId = recipeId
                parsedRecipe = parsed
                parseNotesDismissed = false
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - File import

    func importFromFileContent(content: String, type: String, fileName: String) {
        isImportingFile = true
        errorMessage = nil

        Task {
            defer { isImportingFile = false }
            do {
                let parsed = try await cloudFunctions.parseRecipeContent(content: content, type: type, fileName: fileName)
                let recipeId = UUID().uuidString
                let authorId = authRepository.uid
                let authorName = isOwnRecipe ? (authRepository.displayName ?? "Me") : "Imported"
                _ = try repository.insertFullRecipeFromParsed(
                    recipeId: recipeId,
                    parsed: parsed,
                    authorId: authorId,
                    authorDisplayName: authorName,
                    isImported: !isOwnRecipe,
                    needsReview: true
                )
                reviewingRecipeId = recipeId
                parsedRecipe = parsed
                parseNotesDismissed = false
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - Review sheet actions

    func confirmImport() {
        guard let recipeId = reviewingRecipeId else { return }
        try? repository.confirmReview(recipeId: recipeId)
        // Apply the author toggle: isOwnRecipe true → isImported false (Personal), false → isImported true
        try? repository.updateIsImported(recipeId: recipeId, isImported: !isOwnRecipe)
        parsedRecipe = nil
        reviewingRecipeId = nil
    }

    func reimport() {
        // Delete the pending recipe so we don't leave a duplicate when re-parsing
        if let recipeId = reviewingRecipeId {
            try? repository.deleteRecipe(id: recipeId)
        }
        parsedRecipe = nil
        reviewingRecipeId = nil
    }

    func dismissReview() {
        parsedRecipe = nil
        reviewingRecipeId = nil
    }

    // MARK: - Helpers

    private func recipeToParsed(_ recipe: RecipeModel) -> ParsedRecipeData {
        let sections = recipe.sections.sorted { $0.orderIndex < $1.orderIndex }.map {
            ParsedSection(id: $0.id, name: $0.name, orderIndex: $0.orderIndex)
        }
        let ingredients = recipe.ingredients.sorted { $0.orderIndex < $1.orderIndex }.map { ing in
            ParsedIngredient(
                id: ing.id,
                sectionId: ing.section?.id,
                name: ing.name,
                quantityValue: ing.quantityValue,
                quantityUnit: ing.quantityUnit,
                quantityDisplay: ing.quantityDisplay,
                groupLabel: ing.groupLabel,
                isOptional: ing.isOptional,
                orderIndex: ing.orderIndex,
                quantityValueMetric: ing.quantityValueMetric,
                quantityUnitMetric: ing.quantityUnitMetric,
                quantityDisplayMetric: ing.quantityDisplayMetric,
                quantityValueImperial: ing.quantityValueImperial,
                quantityUnitImperial: ing.quantityUnitImperial,
                quantityDisplayImperial: ing.quantityDisplayImperial
            )
        }
        let steps = recipe.steps.sorted { $0.orderIndex < $1.orderIndex }.map {
            ParsedStep(id: $0.id, sectionId: $0.section?.id, instruction: $0.instruction, orderIndex: $0.orderIndex)
        }
        return ParsedRecipeData(
            title: recipe.title,
            description: recipe.recipeDescription,
            sourceUrls: recipe.sourceUrls,
            baseServings: recipe.baseServings,
            baseServingsMin: recipe.baseServingsMin,
            baseServingsMax: recipe.baseServingsMax,
            prepTimeMinutes: recipe.prepTimeMinutes,
            cookTimeMinutes: recipe.cookTimeMinutes,
            imageUrl: recipe.imageUrl,
            tags: recipe.tags,
            sections: sections,
            ingredients: ingredients,
            steps: steps,
            stepIngredientRefs: [],
            parseNotes: nil
        )
    }
}
