import SwiftData
import Observation
import Foundation

// MARK: - Editor data models

struct EditorIngredient: Identifiable {
    var id: String
    var name: String
    var quantityValue: Double?
    var quantityUnit: String
    var quantityDisplay: String
    var groupLabel: String
    var isOptional: Bool
    // F6 conversion fields — carried so editing doesn't wipe them and the
    // "Update unit conversions" button can populate them.
    var quantityValueMetric: Double? = nil
    var quantityUnitMetric: String? = nil
    var quantityDisplayMetric: String? = nil
    var quantityValueImperial: Double? = nil
    var quantityUnitImperial: String? = nil
    var quantityDisplayImperial: String? = nil
    /// F11: author-entered shopping note (brand/comment); shown on the Shopping List.
    var shoppingNote: String = ""
    // F15 — quantity-range upper bounds (editor sets the original max; conversions come from the button).
    var quantityValueMax: Double? = nil
    var quantityValueMaxMetric: Double? = nil
    var quantityValueMaxImperial: Double? = nil

    init(id: String = UUID().uuidString,
         name: String = "",
         quantityValue: Double? = nil,
         quantityUnit: String = "",
         quantityDisplay: String = "",
         groupLabel: String = "",
         isOptional: Bool = false) {
        self.id = id
        self.name = name
        self.quantityValue = quantityValue
        self.quantityUnit = quantityUnit
        self.quantityDisplay = quantityDisplay
        self.groupLabel = groupLabel
        self.isOptional = isOptional
    }

    init(from model: IngredientModel) {
        self.id = model.id
        self.name = model.name
        self.quantityValue = model.quantityValue
        self.quantityUnit = model.quantityUnit ?? ""
        self.quantityDisplay = model.quantityDisplay ?? ""
        self.groupLabel = model.groupLabel ?? ""
        self.isOptional = model.isOptional
        self.quantityValueMetric = model.quantityValueMetric
        self.quantityUnitMetric = model.quantityUnitMetric
        self.quantityDisplayMetric = model.quantityDisplayMetric
        self.quantityValueImperial = model.quantityValueImperial
        self.quantityUnitImperial = model.quantityUnitImperial
        self.quantityDisplayImperial = model.quantityDisplayImperial
        self.shoppingNote = model.shoppingNote ?? ""
        self.quantityValueMax = model.quantityValueMax
        self.quantityValueMaxMetric = model.quantityValueMaxMetric
        self.quantityValueMaxImperial = model.quantityValueMaxImperial
    }
}

struct EditorStep: Identifiable {
    var id: String
    var instruction: String
    /// F4/F16: ids of the ingredients this step uses (drives inline refs + cooking-mode card).
    var ingredientIds: [String]

    init(id: String = UUID().uuidString, instruction: String = "", ingredientIds: [String] = []) {
        self.id = id
        self.instruction = instruction
        self.ingredientIds = ingredientIds
    }

    init(from model: StepModel) {
        self.id = model.id
        self.instruction = model.instruction
        self.ingredientIds = model.ingredientRefs.compactMap { $0.ingredient?.id }
    }
}

struct EditorSection: Identifiable {
    var id: String
    var name: String
    var ingredients: [EditorIngredient]
    var steps: [EditorStep]

    init(id: String = UUID().uuidString,
         name: String = "New Section",
         ingredients: [EditorIngredient] = [],
         steps: [EditorStep] = []) {
        self.id = id
        self.name = name
        self.ingredients = ingredients
        self.steps = steps
    }

    init(from model: RecipeSectionModel) {
        self.id = model.id
        self.name = model.name
        let sortedIngs = model.ingredients.sorted { $0.orderIndex < $1.orderIndex }
        let sortedSteps = model.steps.sorted { $0.orderIndex < $1.orderIndex }
        self.ingredients = sortedIngs.map { EditorIngredient(from: $0) }
        self.steps = sortedSteps.map { EditorStep(from: $0) }
    }
}

// MARK: - ViewModel

@Observable
@MainActor
final class RecipeEditorViewModel {
    // Metadata fields
    var title: String
    var description: String
    var prepTimeMinutes: String
    var cookTimeMinutes: String
    var baseServings: String
    var baseServingsMin: String
    var baseServingsMax: String
    var tagsText: String        // comma-separated
    var sourceUrlsText: String  // newline-separated

    // Sections (contains ingredients + steps)
    var sections: [EditorSection]

    // Deletion tracking
    var deletedSectionIds: Set<String> = []
    var deletedIngredientIds: Set<String> = []
    var deletedStepIds: Set<String> = []

    var isSaving = false
    var isDeleting = false
    var errorMessage: String? = nil
    var savedRecipeId: String? = nil
    var deleteComplete = false
    /// true = Personal (isImported=false), false = Imported (isImported=true)
    var isPersonalAuthor: Bool = true
    // Variations (F10)
    var isVariant: Bool = false
    var variantName: String = ""
    /// Number of variations this base recipe has (0 when not a base) — used in the delete prompt.
    var variantCount: Int = 0
    static let maxVariantNameLen = 20

    var existingRecipe: RecipeModel?
    private let repository: RecipeRepository
    private let authRepository: AuthRepository
    private let syncService: RecipeSyncService?
    private let sharedRecipeService: SharedRecipeService?
    private let cloudFunctions: CloudFunctionsService?

    // Unit conversions (F6 "Update unit conversions" button)
    var isConverting = false
    var conversionMessage: String? = nil

    /// Display name shown in the "Personal" dropdown option
    var personalAuthorName: String {
        authRepository.displayName ?? authRepository.email ?? "Me"
    }

    // MARK: - Init for new recipe

    init(repository: RecipeRepository, authRepository: AuthRepository, syncService: RecipeSyncService? = nil, sharedRecipeService: SharedRecipeService? = nil, cloudFunctions: CloudFunctionsService? = nil) {
        self.repository = repository
        self.authRepository = authRepository
        self.syncService = syncService
        self.sharedRecipeService = sharedRecipeService
        self.cloudFunctions = cloudFunctions
        self.title = ""
        self.description = ""
        self.prepTimeMinutes = ""
        self.cookTimeMinutes = ""
        self.baseServings = "4"
        self.baseServingsMin = ""
        self.baseServingsMax = ""
        self.tagsText = ""
        self.sourceUrlsText = ""
        // Start with one unnamed section
        self.sections = [EditorSection(id: UUID().uuidString, name: "Main", ingredients: [], steps: [])]
        self.isPersonalAuthor = true // new recipes default to Personal
    }

    // MARK: - Init for editing existing recipe

    init(recipe: RecipeModel,
         repository: RecipeRepository,
         authRepository: AuthRepository,
         syncService: RecipeSyncService? = nil,
         sharedRecipeService: SharedRecipeService? = nil,
         cloudFunctions: CloudFunctionsService? = nil,
         forking: Bool = false) {
        self.repository = repository
        self.authRepository = authRepository
        self.syncService = syncService
        self.sharedRecipeService = sharedRecipeService
        self.cloudFunctions = cloudFunctions
        self.existingRecipe = forking ? nil : recipe
        self.title = forking ? "\(recipe.title) (copy)" : recipe.title
        self.description = recipe.recipeDescription ?? ""
        self.prepTimeMinutes = recipe.prepTimeMinutes.map { String($0) } ?? ""
        self.cookTimeMinutes = recipe.cookTimeMinutes.map { String($0) } ?? ""
        self.baseServings = String(recipe.baseServings)
        self.baseServingsMin = recipe.baseServingsMin.map { String($0) } ?? ""
        self.baseServingsMax = recipe.baseServingsMax.map { String($0) } ?? ""
        self.tagsText = recipe.tags.joined(separator: ", ")
        self.sourceUrlsText = recipe.sourceUrls.joined(separator: "\n")

        // Load sections (sorted), with their ingredients and steps
        let sortedSections = recipe.sections.sorted { $0.orderIndex < $1.orderIndex }
        if sortedSections.isEmpty {
            // Recipe has no sections — put all ingredients/steps into a virtual "Main" section
            let allIngredients = recipe.ingredients
                .filter { $0.section == nil }
                .sorted { $0.orderIndex < $1.orderIndex }
                .map { EditorIngredient(from: $0) }
            let allSteps = recipe.steps
                .filter { $0.section == nil }
                .sorted { $0.orderIndex < $1.orderIndex }
                .map { EditorStep(from: $0) }
            self.sections = [EditorSection(
                id: UUID().uuidString,
                name: "Main",
                ingredients: allIngredients,
                steps: allSteps
            )]
        } else {
            self.sections = sortedSections.map { EditorSection(from: $0) }
        }
        // Default: imported recipes start as "Imported"; personal/freeform as "Personal"
        self.isPersonalAuthor = !recipe.isImported
        // F10 variations
        self.isVariant = recipe.parentRecipeId != nil
        self.variantName = recipe.variantName ?? ""
        if !forking, recipe.parentRecipeId == nil {
            self.variantCount = (try? repository.getVariants(parentId: recipe.id).count) ?? 0
        }
    }

    func updateVariantName(_ v: String) {
        variantName = String(v.prefix(Self.maxVariantNameLen))
    }

    // MARK: - Section CRUD

    func addSection() {
        sections.append(EditorSection())
    }

    func deleteSection(at offsets: IndexSet) {
        for idx in offsets {
            let sec = sections[idx]
            deletedSectionIds.insert(sec.id)
            deletedIngredientIds.formUnion(sec.ingredients.map(\.id))
            deletedStepIds.formUnion(sec.steps.map(\.id))
        }
        sections.remove(atOffsets: offsets)
    }

    func moveSection(from: IndexSet, to: Int) {
        sections.move(fromOffsets: from, toOffset: to)
    }

    // MARK: - Ingredient CRUD

    func addIngredient(to sectionId: String) {
        guard let idx = sections.firstIndex(where: { $0.id == sectionId }) else { return }
        sections[idx].ingredients.append(EditorIngredient())
    }

    func deleteIngredient(from sectionId: String, at offsets: IndexSet) {
        guard let sIdx = sections.firstIndex(where: { $0.id == sectionId }) else { return }
        for iIdx in offsets {
            deletedIngredientIds.insert(sections[sIdx].ingredients[iIdx].id)
        }
        sections[sIdx].ingredients.remove(atOffsets: offsets)
    }

    func moveIngredient(in sectionId: String, from: IndexSet, to: Int) {
        guard let sIdx = sections.firstIndex(where: { $0.id == sectionId }) else { return }
        sections[sIdx].ingredients.move(fromOffsets: from, toOffset: to)
    }

    // MARK: - Step CRUD

    func addStep(to sectionId: String) {
        guard let idx = sections.firstIndex(where: { $0.id == sectionId }) else { return }
        sections[idx].steps.append(EditorStep())
    }

    func deleteStep(from sectionId: String, at offsets: IndexSet) {
        guard let sIdx = sections.firstIndex(where: { $0.id == sectionId }) else { return }
        for iIdx in offsets {
            deletedStepIds.insert(sections[sIdx].steps[iIdx].id)
        }
        sections[sIdx].steps.remove(atOffsets: offsets)
    }

    func moveStep(in sectionId: String, from: IndexSet, to: Int) {
        guard let sIdx = sections.firstIndex(where: { $0.id == sectionId }) else { return }
        sections[sIdx].steps.move(fromOffsets: from, toOffset: to)
    }

    // MARK: - Save

    func save() async {
        guard !title.trimmed.isEmpty else {
            errorMessage = "Title is required."
            return
        }
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }

        let tags = tagsText.split(separator: ",").map { String($0).trimmed }.filter { !$0.isEmpty }
        let urls = sourceUrlsText.split(separator: "\n").map { String($0).trimmed }.filter { !$0.isEmpty }
        let prep = Int(prepTimeMinutes)
        let cook = Int(cookTimeMinutes)
        let servings = Int(baseServings) ?? 4
        let servingsMin = Int(baseServingsMin)
        let servingsMax = Int(baseServingsMax)

        do {
            if let existing = existingRecipe {
                // Update existing recipe (delta save)
                try repository.updateFullRecipe(
                    recipeId: existing.id,
                    title: title.trimmed,
                    recipeDescription: description.trimmed.isEmpty ? nil : description.trimmed,
                    sourceUrls: urls,
                    baseServings: servings,
                    baseServingsMin: servingsMin,
                    baseServingsMax: servingsMax,
                    prepTimeMinutes: prep,
                    cookTimeMinutes: cook,
                    tags: tags,
                    isImported: !isPersonalAuthor,
                    authorDisplayName: isPersonalAuthor ? personalAuthorName : "Imported",
                    variantName: isVariant ? (variantName.trimmed.isEmpty ? "Variation" : variantName.trimmed) : nil,
                    sections: sections,
                    deletedSectionIds: Array(deletedSectionIds),
                    deletedIngredientIds: Array(deletedIngredientIds),
                    deletedStepIds: Array(deletedStepIds)
                )
                savedRecipeId = existing.id
                // Push to Firestore
                if let updated = try repository.fetchRecipe(id: existing.id) {
                    await syncService?.pushPersonalRecipe(updated)
                    // If public, re-publish the canonical mirror so receivers pick up edits.
                    if updated.visibility == "public" {
                        _ = await sharedRecipeService?.publish(updated)
                    }
                }
            } else {
                // New recipe — build a ParsedRecipeData and insert
                let recipeId = UUID().uuidString
                let parsed = buildParsedRecipeData()
                _ = try repository.insertFullRecipeFromParsed(
                    recipeId: recipeId,
                    parsed: parsed,
                    authorId: authRepository.uid,
                    authorDisplayName: isPersonalAuthor ? personalAuthorName : "Imported",
                    isImported: !isPersonalAuthor,
                    needsReview: false
                )
                savedRecipeId = recipeId
                if let inserted = try repository.fetchRecipe(id: recipeId) {
                    await syncService?.pushPersonalRecipe(inserted)
                }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Update unit conversions (F6)

    /// Re-derive metric/imperial conversions for all ingredients via the
    /// `convertIngredients` Cloud Function, then merge into editor state.
    /// The user must Save afterwards to persist.
    func updateConversions() {
        let allIngs = sections.flatMap { $0.ingredients }
        guard !allIngs.isEmpty, let cf = cloudFunctions else { return }
        isConverting = true
        Task {
            do {
                let payload: [[String: Any]] = allIngs.map { ing in
                    ["id": ing.id, "name": ing.name, "quantityDisplay": ing.quantityDisplay]
                }
                let byId = try await cf.convertIngredients(payload)
                sections = sections.map { section in
                    var s = section
                    s.ingredients = section.ingredients.map { ing in
                        guard let r = byId[ing.id] else { return ing }
                        var updated = ing
                        updated.quantityValueMetric = (r["quantityValueMetric"] as? NSNumber)?.doubleValue
                        updated.quantityUnitMetric = r["quantityUnitMetric"] as? String
                        updated.quantityDisplayMetric = r["quantityDisplayMetric"] as? String
                        updated.quantityValueImperial = (r["quantityValueImperial"] as? NSNumber)?.doubleValue
                        updated.quantityUnitImperial = r["quantityUnitImperial"] as? String
                        updated.quantityDisplayImperial = r["quantityDisplayImperial"] as? String
                        // F15: backend supplies the scaled range maxes alongside the conversions
                        updated.quantityValueMaxMetric = (r["quantityValueMaxMetric"] as? NSNumber)?.doubleValue
                        updated.quantityValueMaxImperial = (r["quantityValueMaxImperial"] as? NSNumber)?.doubleValue
                        return updated
                    }
                    return s
                }
                isConverting = false
                conversionMessage = "Conversions updated — Save to keep them"
            } catch {
                isConverting = false
                conversionMessage = "Conversion failed: \(error.localizedDescription)"
            }
        }
    }

    func clearConversionMessage() { conversionMessage = nil }

    // MARK: - Delete

    func deleteRecipe() {
        guard let existing = existingRecipe else { return }
        let wasPublic = existing.visibility == "public"
        let id = existing.id
        let isBase = existing.parentRecipeId == nil
        isDeleting = true
        Task {
            // F10: deleting a base cascade-deletes its variations (else they're orphaned).
            if isBase, let variations = try? repository.getVariants(parentId: id) {
                for v in variations {
                    let vId = v.id
                    let vPublic = v.visibility == "public"
                    try? repository.deleteRecipe(id: vId)
                    await syncService?.deletePersonalRecipe(vId)
                    if vPublic { _ = await sharedRecipeService?.unpublish(vId) }
                }
            }
            try? repository.deleteRecipe(id: id)
            // Remove the cloud copy too, otherwise the next pull resurrects it.
            await syncService?.deletePersonalRecipe(id)
            // If it was public, take down the shared mirror so receivers lose access.
            if wasPublic { _ = await sharedRecipeService?.unpublish(id) }
            isDeleting = false
            deleteComplete = true
        }
    }

    // MARK: - Private helpers

    private func buildParsedRecipeData() -> ParsedRecipeData {
        let tags = tagsText.split(separator: ",").map { String($0).trimmed }.filter { !$0.isEmpty }
        let urls = sourceUrlsText.split(separator: "\n").map { String($0).trimmed }.filter { !$0.isEmpty }

        var parsedSections: [ParsedSection] = []
        var parsedIngredients: [ParsedIngredient] = []
        var parsedSteps: [ParsedStep] = []

        for (sIdx, edSec) in sections.enumerated() {
            parsedSections.append(ParsedSection(id: edSec.id, name: edSec.name, orderIndex: sIdx))
            for (iIdx, edIng) in edSec.ingredients.enumerated() {
                parsedIngredients.append(ParsedIngredient(
                    id: edIng.id,
                    sectionId: edSec.id,
                    name: edIng.name,
                    quantityValue: edIng.quantityValue,
                    quantityUnit: edIng.quantityUnit.isEmpty ? nil : edIng.quantityUnit,
                    quantityDisplay: edIng.quantityDisplay.isEmpty ? nil : edIng.quantityDisplay,
                    groupLabel: edIng.groupLabel.isEmpty ? nil : edIng.groupLabel,
                    isOptional: edIng.isOptional,
                    orderIndex: iIdx,
                    quantityValueMetric: nil,
                    quantityUnitMetric: nil,
                    quantityDisplayMetric: nil,
                    quantityValueImperial: nil,
                    quantityUnitImperial: nil,
                    quantityDisplayImperial: nil
                ))
            }
            for (stIdx, edStep) in edSec.steps.enumerated() {
                parsedSteps.append(ParsedStep(
                    id: edStep.id,
                    sectionId: edSec.id,
                    instruction: edStep.instruction,
                    orderIndex: stIdx
                ))
            }
        }

        return ParsedRecipeData(
            title: title.trimmed,
            description: description.trimmed.isEmpty ? nil : description.trimmed,
            sourceUrls: urls,
            baseServings: Int(baseServings) ?? 4,
            baseServingsMin: Int(baseServingsMin),
            baseServingsMax: Int(baseServingsMax),
            prepTimeMinutes: Int(prepTimeMinutes),
            cookTimeMinutes: Int(cookTimeMinutes),
            imageUrl: nil,
            tags: tags,
            sections: parsedSections,
            ingredients: parsedIngredients,
            steps: parsedSteps,
            stepIngredientRefs: [],
            parseNotes: nil
        )
    }
}
