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
            let ings = recipe.ingredients
                .sorted { $0.orderIndex < $1.orderIndex }.map { EditorIngredient(from: $0) }
            let steps = recipe.steps
                .sorted { $0.orderIndex < $1.orderIndex }.map { EditorStep(from: $0) }
            editSections = [EditorSection(id: UUID().uuidString, name: "", ingredients: ings, steps: steps)]
        } else {
            editSections = sorted.map { EditorSection(from: $0) }
            // F21: orphan ingredients/steps (null/unknown section) would otherwise be dropped on
            // edit — assign them to the first section so they line up with everything else.
            let knownIngIds = Set(editSections.flatMap { $0.ingredients.map(\.id) })
            let orphanIngs = recipe.ingredients.filter { !knownIngIds.contains($0.id) }
                .sorted { $0.orderIndex < $1.orderIndex }.map { EditorIngredient(from: $0) }
            let knownStepIds = Set(editSections.flatMap { $0.steps.map(\.id) })
            let orphanSteps = recipe.steps.filter { !knownStepIds.contains($0.id) }
                .sorted { $0.orderIndex < $1.orderIndex }.map { EditorStep(from: $0) }
            if !orphanIngs.isEmpty { editSections[0].ingredients.append(contentsOf: orphanIngs) }
            if !orphanSteps.isEmpty { editSections[0].steps.append(contentsOf: orphanSteps) }
        }
        deletedSectionIds = []; deletedIngredientIds = []; deletedStepIds = []
        editIngredientSig = Dictionary(uniqueKeysWithValues:
            editSections.flatMap { $0.ingredients }.map { ($0.id, Self.ingredientSig($0)) })
        editError = nil
        isEditMode = true
    }

    /// Conversion-relevant fingerprint of an ingredient (name + quantity) — drives "what changed".
    static func ingredientSig(_ i: EditorIngredient) -> String {
        "\(i.name.trimmed)|\(i.quantityDisplay.trimmed)|\(i.quantityUnit.trimmed)|\(String(describing: i.quantityValue))|\(String(describing: i.quantityValueMax))"
    }

    /// Call the convertIngredients CF for `ings`; returns id → raw conversion fields.
    private func convertRemote(_ ings: [EditorIngredient]) async -> [String: [String: Any]] {
        guard !ings.isEmpty, let cf = cloudFunctions else { return [:] }
        let payload: [[String: Any]] = ings.map { ["id": $0.id, "name": $0.name, "quantityDisplay": $0.quantityDisplay] }
        return (try? await cf.convertIngredients(payload)) ?? [:]
    }

    private func withConversions(_ ing: EditorIngredient, _ r: [String: Any]) -> EditorIngredient {
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
        // F19: move the substitute to sit immediately after its target within that section, so the
        // group reads together in the list.
        guard let ts = editSections.firstIndex(where: { $0.ingredients.contains { $0.id == targetId } }) else { return }
        var moved: EditorIngredient?
        for s in editSections.indices {
            if let ii = editSections[s].ingredients.firstIndex(where: { $0.id == ingredientId }) {
                moved = editSections[s].ingredients.remove(at: ii); break
            }
        }
        guard let item = moved else { return }
        if let ti = editSections[ts].ingredients.firstIndex(where: { $0.id == targetId }) {
            editSections[ts].ingredients.insert(item, at: ti + 1)
        } else {
            editSections[ts].ingredients.append(item)
        }
    }

    // F21: section-agnostic ops — locate an item by id across all sections so changing its section
    // mid-edit doesn't break the open sheet.

    /// The section currently holding an ingredient / step.
    func sectionId(ofIngredient id: String) -> String? {
        editSections.first { $0.ingredients.contains { $0.id == id } }?.id
    }
    func sectionId(ofStep id: String) -> String? {
        editSections.first { $0.steps.contains { $0.id == id } }?.id
    }

    func updateIngredientAnywhere(_ updated: EditorIngredient) {
        for s in editSections.indices {
            if let i = editSections[s].ingredients.firstIndex(where: { $0.id == updated.id }) {
                editSections[s].ingredients[i] = updated; return
            }
        }
    }
    func updateStepAnywhere(_ updated: EditorStep) {
        for s in editSections.indices {
            if let i = editSections[s].steps.firstIndex(where: { $0.id == updated.id }) {
                editSections[s].steps[i] = updated; return
            }
        }
    }
    func deleteIngredientAnywhere(_ id: String) {
        for s in editSections.indices { editSections[s].ingredients.removeAll { $0.id == id } }
        deletedIngredientIds.insert(id)
        for s in editSections.indices {
            for j in editSections[s].steps.indices { editSections[s].steps[j].ingredientIds.removeAll { $0 == id } }
        }
    }
    func deleteStepAnywhere(_ id: String) {
        for s in editSections.indices { editSections[s].steps.removeAll { $0.id == id } }
        deletedStepIds.insert(id)
    }

    /// Move an ingredient / step to a different section (F21 — the sheet's Section selector).
    func moveIngredientToSection(_ ingredientId: String, to newSectionId: String) {
        guard let target = editSections.firstIndex(where: { $0.id == newSectionId }) else { return }
        for s in editSections.indices {
            if let i = editSections[s].ingredients.firstIndex(where: { $0.id == ingredientId }) {
                guard s != target else { return }
                let item = editSections[s].ingredients.remove(at: i)
                editSections[target].ingredients.append(item)
                return
            }
        }
    }
    func moveStepToSection(_ stepId: String, to newSectionId: String) {
        guard let target = editSections.firstIndex(where: { $0.id == newSectionId }) else { return }
        for s in editSections.indices {
            if let i = editSections[s].steps.firstIndex(where: { $0.id == stepId }) {
                guard s != target else { return }
                let item = editSections[s].steps.remove(at: i)
                editSections[target].steps.append(item)
                return
            }
        }
    }

    /// Create a new section and return its id (for the sheet's "New section" option).
    @discardableResult
    func addSectionReturningId(name: String = "New Section") -> String {
        let sec = EditorSection(name: name.trimmed.isEmpty ? "New Section" : name, ingredients: [], steps: [])
        editSections.append(sec)
        return sec.id
    }

    /// Reorder an ingredient within its section (Up/Down in the ingredient sheet).
    func moveIngredient(in sectionId: String, _ ingredientId: String, by delta: Int) {
        guard let s = editSections.firstIndex(where: { $0.id == sectionId }),
              let i = editSections[s].ingredients.firstIndex(where: { $0.id == ingredientId }) else { return }
        let j = i + delta
        guard editSections[s].ingredients.indices.contains(j) else { return }
        editSections[s].ingredients.swapAt(i, j)
    }

    /// Reorder a step within its section (Up/Down in the step sheet).
    func moveStep(in sectionId: String, _ stepId: String, by delta: Int) {
        guard let s = editSections.firstIndex(where: { $0.id == sectionId }),
              let i = editSections[s].steps.firstIndex(where: { $0.id == stepId }) else { return }
        let j = i + delta
        guard editSections[s].steps.indices.contains(j) else { return }
        editSections[s].steps.swapAt(i, j)
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

    /// #9 — Auto-arrange ingredients from the step text (local, no Gemini). Per section: link any
    /// ingredient not referenced by a step to the first step that mentions it (by a significant word
    /// of its name, e.g. "oil" in "olive oil"), then reorder the ingredient list by first-mention
    /// order (unmentioned keep their order at the end). Explicit edit-menu action so it never
    /// silently overrides a manual reorder.
    func autoArrangeFromSteps() {
        let stop: Set<String> = ["the","and","for","with","to","of","an","into","until","then","your","you",
            "add","stir","cook","heat","mix","over","from","this","that","each","about","minutes","minute"]
        func tokens(_ s: String) -> [String] {
            s.lowercased().split { !$0.isLetter && !$0.isNumber }.map(String.init)
                .filter { $0.count >= 3 && !stop.contains($0) }
        }
        for sIdx in editSections.indices {
            let sec = editSections[sIdx]
            var firstMention: [String: Int] = [:]
            for (si, step) in sec.steps.enumerated() {
                let words = Set(step.instruction.lowercased().split { !$0.isLetter && !$0.isNumber }.map(String.init))
                for ing in sec.ingredients where firstMention[ing.id] == nil
                    && !ing.name.trimmed.isEmpty && tokens(ing.name).contains(where: { words.contains($0) }) {
                    firstMention[ing.id] = si
                }
            }
            let linkedAnywhere = Set(sec.steps.flatMap { $0.ingredientIds })
            for stIdx in editSections[sIdx].steps.indices {
                let toAdd = sec.ingredients
                    .filter { firstMention[$0.id] == stIdx && !linkedAnywhere.contains($0.id) }
                    .map { $0.id }
                if !toAdd.isEmpty {
                    var ids = editSections[sIdx].steps[stIdx].ingredientIds
                    for id in toAdd where !ids.contains(id) { ids.append(id) }
                    editSections[sIdx].steps[stIdx].ingredientIds = ids
                }
            }
            // Stable sort by first-mention order; ties (incl. unmentioned = .max) keep input order.
            editSections[sIdx].ingredients = sec.ingredients.enumerated()
                .sorted { a, b in
                    let ka = firstMention[a.element.id] ?? Int.max
                    let kb = firstMention[b.element.id] ?? Int.max
                    return ka == kb ? a.offset < b.offset : ka < kb
                }
                .map { $0.element }
        }
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
                // #8 — auto-update conversions ONLY for ingredients whose name/quantity changed
                // (or were added) this session. Best-effort: a convert failure doesn't block save.
                let changed = editSections.flatMap { $0.ingredients }.filter {
                    !$0.quantityDisplay.trimmed.isEmpty && editIngredientSig[$0.id] != Self.ingredientSig($0)
                }
                let converted = await convertRemote(changed)
                let effectiveSections: [EditorSection] = converted.isEmpty ? editSections : editSections.map { sec in
                    var s = sec
                    s.ingredients = sec.ingredients.map { ing in
                        converted[ing.id].map { withConversions(ing, $0) } ?? ing
                    }
                    return s
                }

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
                    sections: effectiveSections,
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
        guard !allIngs.isEmpty, cloudFunctions != nil else { return }
        isConverting = true
        Task {
            let byId = await convertRemote(allIngs)
            if byId.isEmpty {
                isConverting = false
                conversionMessage = "Conversion failed — try again."
                return
            }
            editSections = editSections.map { section in
                var s = section
                s.ingredients = section.ingredients.map { ing in
                    byId[ing.id].map { withConversions(ing, $0) } ?? ing
                }
                return s
            }
            isConverting = false
            conversionMessage = "Conversions updated — Save to keep them"
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
