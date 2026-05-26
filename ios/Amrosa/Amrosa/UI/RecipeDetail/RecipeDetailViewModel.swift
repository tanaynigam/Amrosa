import SwiftData
import Observation
import Foundation

@Observable
@MainActor
final class RecipeDetailViewModel {
    var recipe: RecipeModel
    var selectedServings: Int
    var scaleAnchorQty: Double          // tracks anchor-based scale; same as base when not anchor-mode
    var unitMode: UnitMode = .original
    var checkedIngredientIds: Set<String> = []
    var selectedSubstitutes: [String: String] = [:]  // substituteGroupId → chosen ingredientId
    var showOptional = false
    var newNoteText = ""
    var isEditingNote: RecipeNoteModel? = nil
    var editingNoteText = ""

    private let repository: RecipeRepository
    private let authRepository: AuthRepository

    init(recipe: RecipeModel, repository: RecipeRepository, authRepository: AuthRepository) {
        self.recipe = recipe
        self.repository = repository
        self.authRepository = authRepository
        self.selectedServings = recipe.baseServings
        // Seed anchor quantity from the anchor ingredient's base value
        if let anchorId = recipe.scaleIngredientId,
           let anchor = recipe.ingredients.first(where: { $0.id == anchorId }),
           let baseValue = anchor.quantityValue {
            self.scaleAnchorQty = baseValue
        } else {
            self.scaleAnchorQty = 0
        }
    }

    // MARK: - Scaling

    var isAnchorBased: Bool {
        recipe.scaleIngredientId != nil
    }

    private var baseAnchorQty: Double? {
        guard let anchorId = recipe.scaleIngredientId,
              let anchor = recipe.ingredients.first(where: { $0.id == anchorId }) else { return nil }
        return anchor.quantityValue
    }

    var scaleFactor: Double {
        if let base = baseAnchorQty, base > 0 {
            return scaleAnchorQty / base
        }
        return Double(selectedServings) / Double(recipe.baseServings)
    }

    /// Display string for the current yield (accounting for anchor-based scaling).
    var yieldDisplay: String {
        if isAnchorBased,
           let anchorId = recipe.scaleIngredientId,
           let anchor = recipe.ingredients.first(where: { $0.id == anchorId }) {
            let scaled = QuantityScaler.scale(
                quantityValue: scaleAnchorQty,
                quantityUnit: anchor.quantityUnit,
                quantityDisplay: nil,
                scale: 1.0
            )
            return "\(scaled) \(anchor.name)"
        }
        // Servings range
        if let min = recipe.baseServingsMin, let max = recipe.baseServingsMax {
            let ratio = Double(selectedServings) / Double(recipe.baseServings)
            let scaledMin = Int(round(Double(min) * ratio))
            let scaledMax = Int(round(Double(max) * ratio))
            return "\(scaledMin)–\(scaledMax)"
        }
        return "\(selectedServings)"
    }

    var canDecrement: Bool {
        if isAnchorBased {
            return scaleAnchorQty > recipe.scaleStep
        }
        return selectedServings > 1
    }

    func adjustScale(delta: Int) {
        if let base = baseAnchorQty {
            let step = recipe.scaleStep
            let newQty = scaleAnchorQty + Double(delta) * step
            scaleAnchorQty = max(step, newQty)
        } else {
            let newServings = selectedServings + delta
            if newServings >= 1 { selectedServings = newServings }
        }
    }

    func resetScale() {
        if let base = baseAnchorQty {
            scaleAnchorQty = base
        } else {
            selectedServings = recipe.baseServings
        }
    }

    var hasConversionData: Bool {
        recipe.ingredients.contains { $0.hasConversionData }
    }

    func scaledQuantity(for ingredient: IngredientModel) -> String {
        QuantityScaler.scale(ingredient: ingredient, scaleFactor: scaleFactor, unitMode: unitMode)
    }

    // MARK: - Ownership

    var isOwner: Bool {
        guard let uid = authRepository.uid else { return false }
        guard let authorId = recipe.authorId else { return false }
        return authorId == uid
    }

    // MARK: - Sections / ingredients

    var sortedSections: [RecipeSectionModel] {
        recipe.sections.sorted { $0.orderIndex < $1.orderIndex }
    }

    func ingredients(for section: RecipeSectionModel?) -> [IngredientModel] {
        let all = recipe.ingredients.filter { $0.section?.id == section?.id }
        return all.sorted { $0.orderIndex < $1.orderIndex }
    }

    func steps(for section: RecipeSectionModel?) -> [StepModel] {
        let all = recipe.steps.filter { $0.section?.id == section?.id }
        return all.sorted { $0.orderIndex < $1.orderIndex }
    }

    func visibleIngredients(for section: RecipeSectionModel?) -> [IngredientModel] {
        ingredients(for: section).filter { ing in
            guard ing.isOptional else {
                // For substitute groups, show only selected substitute
                if let gid = ing.substituteGroupId {
                    let groupMembers = recipe.ingredients.filter { $0.substituteGroupId == gid }
                    let selected = selectedSubstitutes[gid]
                    if let s = selected { return ing.id == s }
                    return groupMembers.first?.id == ing.id
                }
                return true
            }
            return showOptional
        }
    }

    // MARK: - Notes

    func addNote() {
        guard !newNoteText.isEmpty else { return }
        try? repository.addNote(to: recipe.id, content: newNoteText)
        newNoteText = ""
    }

    func saveEditingNote() {
        guard let note = isEditingNote else { return }
        try? repository.updateNote(note, content: editingNoteText)
        isEditingNote = nil
        editingNoteText = ""
    }

    func deleteNote(_ note: RecipeNoteModel) {
        try? repository.deleteNote(note)
    }

    var sortedNotes: [RecipeNoteModel] {
        recipe.notes.sorted { $0.createdAt < $1.createdAt }
    }
}
