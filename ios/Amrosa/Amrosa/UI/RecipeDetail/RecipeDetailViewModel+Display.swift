import Foundation

// MARK: - Unified display layer (F16)
// The detail body renders these regardless of mode. In view mode they map from the real
// RecipeModel (scaled by the active yield/unit); in edit mode from the live draft at 1×/original.

extension RecipeDetailViewModel {

    /// Effective scale + unit for quantity display. Identical in both modes so flipping into edit
    /// changes NOTHING on screen except the jiggle/outline affordance.
    private var effScale: Double { scaleFactor }
    private var effUnit: UnitMode { unitMode }

    // ── Header metadata (draft in edit mode, recipe otherwise) ──

    var displayTitle: String { isEditMode ? editTitle : recipe.title }
    var displayDescription: String? {
        let d = isEditMode ? editDescription : (recipe.recipeDescription ?? "")
        return d.trimmed.isEmpty ? nil : d
    }
    var displayPrepText: String? {
        if isEditMode { return editPrepTime.isEmpty ? nil : "Prep \(editPrepTime)m" }
        return recipe.prepTimeMinutes.map { "Prep  \($0.timeDisplayString)" }
    }
    var displayCookText: String? {
        if isEditMode { return editCookTime.isEmpty ? nil : "Cook \(editCookTime)m" }
        return recipe.cookTimeMinutes.map { "Cook  \($0.timeDisplayString)" }
    }
    /// Static yield label for edit mode (view mode uses the interactive YieldAdjuster).
    var displayEditYield: String {
        editIsRangeYield ? "Yield \(editServingsMin)–\(editServingsMax)" : "Serves \(editBaseServings)"
    }
    var displayTags: String? {
        let t = isEditMode ? editTagsText : recipe.tags.joined(separator: ", ")
        return t.trimmed.isEmpty ? nil : t
    }

    // ── Sections ──

    /// A draft that's just one nameless section behaves like a "no sections" recipe (steps render
    /// flat, no section header) so the view↔edit layout matches exactly for simple recipes.
    private var editIsSingleUnnamed: Bool {
        isEditMode && editSections.count == 1 && editSections[0].name.trimmed.isEmpty
    }

    var displaySections: [DisplaySection] {
        if isEditMode {
            if editIsSingleUnnamed { return [] }
            return editSections.map { DisplaySection(id: $0.id, name: $0.name) }
        }
        return sortedSections.map { DisplaySection(id: $0.id, name: $0.name) }
    }

    var isMultiSection: Bool {
        isEditMode ? editSections.count > 1 : sortedSections.count > 1
    }

    /// Section id to target when editing a step rendered in the flat (nil-section) path.
    var flatStepsSectionId: String? { editIsSingleUnnamed ? editSections[0].id : nil }

    // ── Ingredient blocks ──

    private func displayEditIngredient(_ ing: EditorIngredient) -> DisplayIngredient {
        // Unit-aware + scaled exactly like the read-only rows, so entering edit mode is seamless.
        let scaled: String
        switch effUnit {
        case .metric where ing.quantityValueMetric != nil:
            scaled = QuantityScaler.scale(quantityValue: ing.quantityValueMetric, quantityValueMax: ing.quantityValueMaxMetric,
                                          quantityUnit: ing.quantityUnitMetric, quantityDisplay: ing.quantityDisplayMetric, scale: effScale)
        case .imperial where ing.quantityValueImperial != nil:
            scaled = QuantityScaler.scale(quantityValue: ing.quantityValueImperial, quantityValueMax: ing.quantityValueMaxImperial,
                                          quantityUnit: ing.quantityUnitImperial, quantityDisplay: ing.quantityDisplayImperial, scale: effScale)
        default:
            scaled = QuantityScaler.scale(quantityValue: ing.quantityValue, quantityValueMax: ing.quantityValueMax,
                                          quantityUnit: ing.quantityUnit.isEmpty ? nil : ing.quantityUnit,
                                          quantityDisplay: ing.quantityDisplay.isEmpty ? nil : ing.quantityDisplay, scale: effScale)
        }
        return DisplayIngredient(
            id: ing.id, sectionId: nil, name: ing.name,
            scaledQuantity: scaled, isOptional: ing.isOptional, isOptionalEnabled: false,
            shoppingNote: ing.shoppingNote.trimmed.isEmpty ? nil : ing.shoppingNote
        )
    }

    private func displayViewIngredient(_ ing: IngredientModel) -> DisplayIngredient {
        DisplayIngredient(
            id: ing.id, sectionId: ing.section?.id, name: ing.name,
            scaledQuantity: QuantityScaler.scale(ingredient: ing, scaleFactor: effScale, unitMode: effUnit),
            isOptional: ing.isOptional,
            isOptionalEnabled: enabledOptionals.contains(ing.id),
            shoppingNote: ing.shoppingNote?.trimmed.isEmpty == false ? ing.shoppingNote : nil
        )
    }

    private func groupEdit(_ ings: [EditorIngredient]) -> [DisplayIngredientGroup] {
        guard !ings.isEmpty else { return [] }
        var result: [DisplayIngredientGroup] = []
        let ungrouped = ings.filter { $0.groupLabel.trimmed.isEmpty }
        if !ungrouped.isEmpty {
            result.append(DisplayIngredientGroup(id: "__ungrouped__", label: "", items: ungrouped.map(displayEditIngredient)))
        }
        var seen = Set<String>()
        for ing in ings {
            let label = ing.groupLabel.trimmed
            guard !label.isEmpty, !seen.contains(label) else { continue }
            seen.insert(label)
            result.append(DisplayIngredientGroup(id: label, label: label,
                items: ings.filter { $0.groupLabel.trimmed == label }.map(displayEditIngredient)))
        }
        return result
    }

    /// Ingredient blocks for the body. Mirrors the read-only grouping in BOTH modes (empty sections
    /// hidden, sub-header only when multi-section) so flipping into edit doesn't reflow the layout.
    var displayIngredientBlocks: [DisplayIngredientBlock] {
        if isEditMode {
            let multi = !editIsSingleUnnamed && editSections.count > 1
            return editSections.compactMap { sec in
                let groups = groupEdit(sec.ingredients)
                guard !groups.isEmpty else { return nil }
                return DisplayIngredientBlock(id: sec.id, sectionId: sec.id,
                    title: multi ? (sec.name.isEmpty ? "Section" : sec.name) : nil, groups: groups)
            }
        }
        return ingredientSectionBlocks.map { block in
            DisplayIngredientBlock(
                id: block.id,
                sectionId: block.id == "__other__" ? nil : block.id,
                title: block.title,
                groups: block.groups.map { g in
                    DisplayIngredientGroup(id: g.label.isEmpty ? "__ungrouped__" : g.label, label: g.label,
                        items: g.ingredients.map(displayViewIngredient))
                })
        }
    }

    // ── Steps ──

    func displaySteps(forSectionId sectionId: String?) -> [DisplayStep] {
        if isEditMode {
            // Single nameless section renders its steps under the flat (nil-section) path so the
            // layout matches a no-sections recipe in view mode.
            let lookupId = (editIsSingleUnnamed && sectionId == nil) ? editSections[0].id : sectionId
            let steps = editSections.first { $0.id == lookupId }?.steps ?? []
            let all = allDraftIngredients
            return steps.enumerated().map { idx, st in
                DisplayStep(id: st.id, number: idx + 1, instruction: st.instruction,
                    refs: st.ingredientIds.compactMap { id in
                        all.first { $0.id == id }.map { DisplayStepRef(id: $0.id, text: $0.name) }
                    })
            }
        }
        let steps = recipe.steps.filter { $0.section?.id == sectionId }.sorted { $0.orderIndex < $1.orderIndex }
        return steps.enumerated().map { idx, step in
            DisplayStep(id: step.id, number: idx + 1, instruction: step.instruction,
                refs: step.ingredientRefs.compactMap { ref in
                    guard let ing = ref.ingredient else { return nil }
                    let disp = ref.quantityDisplay ?? ing.quantityDisplay ?? ""
                    return DisplayStepRef(id: ing.id, text: "\(disp) \(ing.name)".trimmed)
                })
        }
    }
}
