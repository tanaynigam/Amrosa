import Foundation

// MARK: - Inline edit mode (F16)
// The detail screen renders this live draft so editing keeps scroll context. The draft is
// built from the current recipe; Save maps it back via `updateFullRecipe`.

extension RecipeDetailViewModel {

    var personalAuthorName: String {
        authRepository.displayName ?? authRepository.email ?? "Me"
    }

    /// All draft ingredients across sections (for the step "Uses ingredients" picker).
    var allDraftIngredients: [EditorIngredient] {
        editSections.flatMap { $0.ingredients }.filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    // MARK: Enter / cancel

    func enterEdit() {
        guard isOwner else { return }
        editTitle = recipe.title
        editDescription = recipe.recipeDescription ?? ""
        editPrepTime = recipe.prepTimeMinutes.map(String.init) ?? ""
        editCookTime = recipe.cookTimeMinutes.map(String.init) ?? ""
        editBaseServings = String(recipe.baseServings)
        editIsRangeYield = recipe.baseServingsMin != nil
        editServingsMin = recipe.baseServingsMin.map(String.init) ?? ""
        editServingsMax = recipe.baseServingsMax.map(String.init) ?? ""
        editTagsText = recipe.tags.joined(separator: ", ")
        editSourceUrlsText = recipe.sourceUrls.joined(separator: "\n")
        editIsPersonalAuthor = !recipe.isImported
        editIsVariant = recipe.parentRecipeId != nil
        editVariantName = recipe.variantName ?? ""

        let sorted = recipe.sections.sorted { $0.orderIndex < $1.orderIndex }
        if sorted.isEmpty {
            // No sections — wrap everything in a single unnamed section.
            let ings = recipe.ingredients.filter { $0.section == nil }
                .sorted { $0.orderIndex < $1.orderIndex }.map { EditorIngredient(from: $0) }
            let steps = recipe.steps.filter { $0.section == nil }
                .sorted { $0.orderIndex < $1.orderIndex }.map { EditorStep(from: $0) }
            editSections = [EditorSection(id: UUID().uuidString, name: "", ingredients: ings, steps: steps)]
        } else {
            editSections = sorted.map { EditorSection(from: $0) }
        }
        deletedSectionIds = []; deletedIngredientIds = []; deletedStepIds = []
        editError = nil
        isEditMode = true
    }

    func cancelEdit() {
        isEditMode = false
        editTarget = nil
        editError = nil
    }

    // MARK: Metadata setters (length-capped variant name)

    func setVariantName(_ v: String) {
        editVariantName = String(v.prefix(Self.maxVariantNameLen))
    }

    // MARK: Sections

    func addSection(name: String) {
        let clean = name.trimmingCharacters(in: .whitespaces)
        editSections.append(EditorSection(name: clean.isEmpty ? "New Section" : clean, ingredients: [], steps: []))
    }

    func updateSectionName(_ sectionId: String, _ name: String) {
        guard let i = editSections.firstIndex(where: { $0.id == sectionId }) else { return }
        editSections[i].name = name
    }

    func deleteSection(_ sectionId: String) {
        guard let i = editSections.firstIndex(where: { $0.id == sectionId }) else { return }
        let sec = editSections[i]
        deletedSectionIds.insert(sec.id)
        deletedIngredientIds.formUnion(sec.ingredients.map(\.id))
        deletedStepIds.formUnion(sec.steps.map(\.id))
        editSections.remove(at: i)
    }

    func moveSection(_ sectionId: String, by delta: Int) {
        guard let i = editSections.firstIndex(where: { $0.id == sectionId }) else { return }
        let j = i + delta
        guard editSections.indices.contains(j) else { return }
        editSections.swapAt(i, j)
    }

    // MARK: Ingredients

    func addIngredient(to sectionId: String, _ ingredient: EditorIngredient) {
        guard let i = editSections.firstIndex(where: { $0.id == sectionId }) else { return }
        editSections[i].ingredients.append(ingredient)
    }

    func updateIngredient(in sectionId: String, _ updated: EditorIngredient) {
        guard let s = editSections.firstIndex(where: { $0.id == sectionId }),
              let g = editSections[s].ingredients.firstIndex(where: { $0.id == updated.id }) else { return }
        editSections[s].ingredients[g] = updated
    }

    /// Link `ingredientId` as a substitute for `targetId` (they share a substitute group), or
    /// unlink it when `targetId` is nil. Mirrors Android `linkSubstitute`.
    func linkSubstitute(ingredientId: String, targetId: String?) {
        guard let targetId else {
            mutateIngredient(ingredientId) { $0.substituteGroupId = nil }
            return
        }
        let target = editSections.flatMap { $0.ingredients }.first { $0.id == targetId }
        let groupId = target?.substituteGroupId ?? "subgrp-\(UUID().uuidString)"
        for s in editSections.indices {
            for i in editSections[s].ingredients.indices {
                let id = editSections[s].ingredients[i].id
                if id == ingredientId {
                    editSections[s].ingredients[i].substituteGroupId = groupId
                } else if id == targetId, editSections[s].ingredients[i].substituteGroupId == nil {
                    editSections[s].ingredients[i].substituteGroupId = groupId
                }
            }
        }
    }

    /// Reorder an ingredient within its section (Up/Down in the ingredient sheet).
    func moveIngredient(in sectionId: String, _ ingredientId: String, by delta: Int) {
        guard let s = editSections.firstIndex(where: { $0.id == sectionId }),
              let i = editSections[s].ingredients.firstIndex(where: { $0.id == ingredientId }) else { return }
        let j = i + delta
        guard editSections[s].ingredients.indices.contains(j) else { return }
        editSections[s].ingredients.swapAt(i, j)
    }

    private func mutateIngredient(_ id: String, _ change: (inout EditorIngredient) -> Void) {
        for s in editSections.indices {
            if let i = editSections[s].ingredients.firstIndex(where: { $0.id == id }) {
                change(&editSections[s].ingredients[i]); return
            }
        }
    }

    func deleteIngredient(in sectionId: String, _ ingredientId: String) {
        guard let s = editSections.firstIndex(where: { $0.id == sectionId }) else { return }
        editSections[s].ingredients.removeAll { $0.id == ingredientId }
        deletedIngredientIds.insert(ingredientId)
        // Unlink from any step that used it.
        for i in editSections.indices {
            for j in editSections[i].steps.indices {
                editSections[i].steps[j].ingredientIds.removeAll { $0 == ingredientId }
            }
        }
    }

    // MARK: Steps

    func addStep(to sectionId: String, _ step: EditorStep) {
        guard let i = editSections.firstIndex(where: { $0.id == sectionId }) else { return }
        editSections[i].steps.append(step)
    }

    func updateStep(in sectionId: String, _ updated: EditorStep) {
        guard let s = editSections.firstIndex(where: { $0.id == sectionId }),
              let g = editSections[s].steps.firstIndex(where: { $0.id == updated.id }) else { return }
        editSections[s].steps[g] = updated
    }

    func deleteStep(in sectionId: String, _ stepId: String) {
        guard let s = editSections.firstIndex(where: { $0.id == sectionId }) else { return }
        editSections[s].steps.removeAll { $0.id == stepId }
        deletedStepIds.insert(stepId)
    }

    // MARK: Save

    func saveEdit() {
        let title = editTitle.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { editError = "Title cannot be empty"; return }
        isSavingEdit = true
        editError = nil

        let tags = editTagsText.split(separator: ",").map { String($0).trimmed }.filter { !$0.isEmpty }
        let urls = editSourceUrlsText.split(separator: "\n").map { String($0).trimmed }.filter { !$0.isEmpty }
        let prep = Int(editPrepTime)
        let cook = Int(editCookTime)
        let servings = Int(editBaseServings) ?? 1
        let servingsMin = editIsRangeYield ? Int(editServingsMin) : nil
        let servingsMax = editIsRangeYield ? Int(editServingsMax) : nil
        let recipeId = recipe.id

        Task {
            do {
                try repository.updateFullRecipe(
                    recipeId: recipeId,
                    title: title,
                    recipeDescription: editDescription.trimmed.isEmpty ? nil : editDescription.trimmed,
                    sourceUrls: urls,
                    baseServings: servings,
                    baseServingsMin: servingsMin,
                    baseServingsMax: servingsMax,
                    prepTimeMinutes: prep,
                    cookTimeMinutes: cook,
                    tags: tags,
                    isImported: !editIsPersonalAuthor,
                    authorDisplayName: editIsPersonalAuthor ? personalAuthorName : "Imported",
                    variantName: editIsVariant ? (editVariantName.trimmed.isEmpty ? "Variation" : editVariantName.trimmed) : nil,
                    sections: editSections,
                    deletedSectionIds: Array(deletedSectionIds),
                    deletedIngredientIds: Array(deletedIngredientIds),
                    deletedStepIds: Array(deletedStepIds)
                )
                // Push + reconcile the mirror (SwiftData @Model updates the view in place):
                // re-publish when shared (shared/friends/public), unpublish when private — so a
                // recipe flipped to Private can never linger in the public mirror / Discovery.
                if let updated = try repository.fetchRecipe(id: recipeId) {
                    if !updated.isImported { await syncService?.pushPersonalRecipe(updated) }
                    if updated.visibility != "private" { _ = await sharedRecipeService.publish(updated) }
                    else { _ = await sharedRecipeService.unpublish(updated.id) }
                }
                isSavingEdit = false
                isEditMode = false
                editTarget = nil
            } catch {
                isSavingEdit = false
                editError = "Save failed: \(error.localizedDescription)"
            }
        }
    }

    // MARK: Update unit conversions (F6)

    func updateConversions() {
        let allIngs = editSections.flatMap { $0.ingredients }
        guard !allIngs.isEmpty, let cf = cloudFunctions else { return }
        isConverting = true
        Task {
            do {
                let payload: [[String: Any]] = allIngs.map { ["id": $0.id, "name": $0.name, "quantityDisplay": $0.quantityDisplay] }
                let byId = try await cf.convertIngredients(payload)
                editSections = editSections.map { section in
                    var s = section
                    s.ingredients = section.ingredients.map { ing in
                        guard let r = byId[ing.id] else { return ing }
                        var u = ing
                        u.quantityValueMetric = (r["quantityValueMetric"] as? NSNumber)?.doubleValue
                        u.quantityUnitMetric = r["quantityUnitMetric"] as? String
                        u.quantityDisplayMetric = r["quantityDisplayMetric"] as? String
                        u.quantityValueImperial = (r["quantityValueImperial"] as? NSNumber)?.doubleValue
                        u.quantityUnitImperial = r["quantityUnitImperial"] as? String
                        u.quantityDisplayImperial = r["quantityDisplayImperial"] as? String
                        u.quantityValueMaxMetric = (r["quantityValueMaxMetric"] as? NSNumber)?.doubleValue
                        u.quantityValueMaxImperial = (r["quantityValueMaxImperial"] as? NSNumber)?.doubleValue
                        return u
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

    // MARK: Delete recipe (cascade variations)

    func deleteRecipe() {
        let id = recipe.id
        // Unpublish any shared tier (shared/friends/public), not just public, so no mirror lingers.
        let wasShared = recipe.visibility != "private"
        let isBase = recipe.parentRecipeId == nil
        isDeleting = true
        Task {
            if isBase, let variations = try? repository.getVariants(parentId: id) {
                for v in variations {
                    let vId = v.id
                    let vShared = v.visibility != "private"
                    try? repository.deleteRecipe(id: vId)
                    await syncService?.deletePersonalRecipe(vId)
                    if vShared { _ = await sharedRecipeService.unpublish(vId) }
                }
            }
            try? repository.deleteRecipe(id: id)
            await syncService?.deletePersonalRecipe(id)
            if wasShared { _ = await sharedRecipeService.unpublish(id) }
            isDeleting = false
            recipeDeleted = true
        }
    }
}
