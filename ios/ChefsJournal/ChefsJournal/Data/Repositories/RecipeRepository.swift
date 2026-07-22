import SwiftData
import Foundation

@MainActor
final class RecipeRepository {
    private let context: ModelContext

    init(context: ModelContext) {
        self.context = context
    }

    // MARK: - Fetch

    func fetchAllRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]
        )
        return try context.fetch(descriptor)
    }

    func fetchYoursRecipes() throws -> [RecipeModel] {
        // All user recipes — pending review first, then recency
        let all = try fetchAllRecipes()
        return all
            .filter { $0.isCustomized || $0.isImported || $0.authorId != nil }
            .sorted { lhs, rhs in
                if lhs.needsReview != rhs.needsReview { return lhs.needsReview }
                return lhs.updatedAt > rhs.updatedAt
            }
    }

    /// Push-eligible recipes: personal (not imported) AND owned by me (not received). Never pushes references.
    /// (Variations ARE pushed — they're real personal recipes — so no parentRecipeId filter here.)
    func fetchPersonalRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { !$0.isImported && !$0.isReceived },
            sortBy: [SortDescriptor(\.title)]
        )
        return try context.fetch(descriptor)
    }

    /// Tab 1 — my base recipes only (excludes received references AND variations).
    func fetchMyRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { !$0.isReceived && $0.parentRecipeId == nil },
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]
        )
        return try context.fetch(descriptor)
    }

    /// Tab 2 — base recipes received from other users (read-only references; variations excluded).
    func fetchReceivedRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { $0.isReceived && $0.parentRecipeId == nil },
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]
        )
        return try context.fetch(descriptor)
    }

    /// All variations of a base recipe, oldest first (F10).
    func getVariants(parentId: String) throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { $0.parentRecipeId == parentId },
            sortBy: [SortDescriptor(\.createdAt)]
        )
        return try context.fetch(descriptor)
    }

    /// IDs of all locally-cached received recipes (Tab 2). Used by the received-reference refresh.
    func fetchReceivedRecipeIds() throws -> [String] {
        try fetchReceivedRecipes().map { $0.id }
    }

    func fetchRecipe(id: String) throws -> RecipeModel? {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { $0.id == id }
        )
        return try context.fetch(descriptor).first
    }

    func fetchNeedsReviewRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { $0.needsReview },
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]
        )
        return try context.fetch(descriptor)
    }

    func fetchIngredient(id: String) throws -> IngredientModel? {
        let descriptor = FetchDescriptor<IngredientModel>(
            predicate: #Predicate { $0.id == id }
        )
        return try context.fetch(descriptor).first
    }

    func count() throws -> Int {
        let descriptor = FetchDescriptor<RecipeModel>()
        return try context.fetchCount(descriptor)
    }

    // MARK: - Insert

    func insertFullRecipe(
        _ recipe: RecipeModel,
        sections: [RecipeSectionModel],
        ingredients: [IngredientModel],
        steps: [StepModel],
        refs: [StepIngredientRefModel]
    ) throws {
        context.insert(recipe)
        for section in sections {
            context.insert(section)
            section.recipe = recipe
        }
        for ingredient in ingredients {
            context.insert(ingredient)
            ingredient.recipe = recipe
        }
        for step in steps {
            context.insert(step)
            step.recipe = recipe
        }
        for ref in refs {
            context.insert(ref)
        }
        try context.save()
    }

    func insertFullRecipeFromParsed(
        recipeId: String,
        parsed: ParsedRecipeData,
        authorId: String?,
        authorDisplayName: String?,
        isImported: Bool,
        needsReview: Bool,
        visibility: String = "private"
    ) throws -> RecipeModel {
        let now = Date()
        let recipe = RecipeModel(
            id: recipeId,
            title: parsed.title,
            recipeDescription: parsed.description,
            sourceUrls: parsed.sourceUrls,
            baseServings: parsed.baseServings,
            baseServingsMin: parsed.baseServingsMin,
            baseServingsMax: parsed.baseServingsMax,
            scaleIngredientId: parsed.scaleIngredientId,
            scaleStep: parsed.scaleStep,
            prepTimeMinutes: parsed.prepTimeMinutes,
            cookTimeMinutes: parsed.cookTimeMinutes,
            imageUrl: parsed.imageUrl,
            tags: parsed.tags,
            isCustomized: !isImported,
            isImported: isImported,
            needsReview: needsReview,
            createdAt: now,
            updatedAt: now,
            authorId: authorId,
            authorDisplayName: authorDisplayName,
            visibility: visibility
        )
        context.insert(recipe)

        let sections = parsed.sections.sorted { $0.orderIndex < $1.orderIndex }.map { ps -> RecipeSectionModel in
            let s = RecipeSectionModel(id: ps.id, name: ps.name, orderIndex: ps.orderIndex)
            context.insert(s)
            s.recipe = recipe
            return s
        }
        let sectionMap = Dictionary(uniqueKeysWithValues: sections.map { ($0.id, $0) })

        let ingredients = parsed.ingredients.sorted { $0.orderIndex < $1.orderIndex }.map { pi -> IngredientModel in
            let ing = IngredientModel(
                id: pi.id,
                name: pi.name,
                quantityValue: pi.quantityValue,
                quantityUnit: pi.quantityUnit,
                quantityDisplay: pi.quantityDisplay,
                groupLabel: pi.groupLabel,
                isOptional: pi.isOptional,
                orderIndex: pi.orderIndex,
                quantityValueMetric: pi.quantityValueMetric,
                quantityUnitMetric: pi.quantityUnitMetric,
                quantityDisplayMetric: pi.quantityDisplayMetric,
                quantityValueImperial: pi.quantityValueImperial,
                quantityUnitImperial: pi.quantityUnitImperial,
                quantityDisplayImperial: pi.quantityDisplayImperial,
                shoppingNote: pi.shoppingNote,
                quantityValueMax: pi.quantityValueMax,
                quantityValueMaxMetric: pi.quantityValueMaxMetric,
                quantityValueMaxImperial: pi.quantityValueMaxImperial
            )
            context.insert(ing)
            ing.recipe = recipe
            if let sid = pi.sectionId { ing.section = sectionMap[sid] }
            return ing
        }
        let ingredientMap = Dictionary(uniqueKeysWithValues: ingredients.map { ($0.id, $0) })

        let steps = parsed.steps.sorted { $0.orderIndex < $1.orderIndex }.map { ps -> StepModel in
            let step = StepModel(id: ps.id, instruction: ps.instruction, orderIndex: ps.orderIndex)
            context.insert(step)
            step.recipe = recipe
            if let sid = ps.sectionId { step.section = sectionMap[sid] }
            return step
        }
        let stepMap = Dictionary(uniqueKeysWithValues: steps.map { ($0.id, $0) })

        for ref in parsed.stepIngredientRefs {
            guard let step = stepMap[ref.stepId], let ing = ingredientMap[ref.ingredientId] else { continue }
            let refModel = StepIngredientRefModel(quantityDisplay: ref.quantityDisplay)
            context.insert(refModel)
            refModel.step = step
            refModel.ingredient = ing
        }

        try context.save()
        return recipe
    }

    /// Clean-replace the SYNCED content (sections/ingredients/steps/refs) of an existing recipe
    /// from a Firestore doc, reattaching the fresh children to `existing`. Local-only data
    /// (notes + shopping checks) is preserved because we never touch the recipe's `notes`
    /// relationship or the separate `ShoppingCheckModel` rows.
    ///
    /// Delete order matters for SwiftData cascade: removing steps (which cascade their refs) and
    /// ingredients BEFORE sections means the section cascade has nothing left to double-delete.
    private func replaceSyncedContent(of existing: RecipeModel, from data: [String: Any]) throws {
        let oldSteps = existing.steps          // snapshot before mutating the context
        let oldIngredients = existing.ingredients
        let oldSections = existing.sections
        for step in oldSteps { context.delete(step) }        // cascades each step's stepRefs
        for ing in oldIngredients { context.delete(ing) }
        for section in oldSections { context.delete(section) } // children already gone → no cascade

        let parsed = try firestoreToParsedRecipeData(id: existing.id, data: data)

        let sections = parsed.sections.sorted { $0.orderIndex < $1.orderIndex }.map { ps -> RecipeSectionModel in
            let s = RecipeSectionModel(id: ps.id, name: ps.name, orderIndex: ps.orderIndex)
            context.insert(s); s.recipe = existing; return s
        }
        let sectionMap = Dictionary(uniqueKeysWithValues: sections.map { ($0.id, $0) })

        let ingredients = parsed.ingredients.sorted { $0.orderIndex < $1.orderIndex }.map { pi -> IngredientModel in
            let ing = IngredientModel(
                id: pi.id, name: pi.name,
                quantityValue: pi.quantityValue, quantityUnit: pi.quantityUnit, quantityDisplay: pi.quantityDisplay,
                groupLabel: pi.groupLabel, isOptional: pi.isOptional, orderIndex: pi.orderIndex,
                quantityValueMetric: pi.quantityValueMetric, quantityUnitMetric: pi.quantityUnitMetric,
                quantityDisplayMetric: pi.quantityDisplayMetric, quantityValueImperial: pi.quantityValueImperial,
                quantityUnitImperial: pi.quantityUnitImperial, quantityDisplayImperial: pi.quantityDisplayImperial,
                shoppingNote: pi.shoppingNote,
                quantityValueMax: pi.quantityValueMax,
                quantityValueMaxMetric: pi.quantityValueMaxMetric,
                quantityValueMaxImperial: pi.quantityValueMaxImperial
            )
            context.insert(ing); ing.recipe = existing
            if let sid = pi.sectionId { ing.section = sectionMap[sid] }
            return ing
        }
        let ingredientMap = Dictionary(uniqueKeysWithValues: ingredients.map { ($0.id, $0) })

        let steps = parsed.steps.sorted { $0.orderIndex < $1.orderIndex }.map { ps -> StepModel in
            let step = StepModel(id: ps.id, instruction: ps.instruction, orderIndex: ps.orderIndex)
            context.insert(step); step.recipe = existing
            if let sid = ps.sectionId { step.section = sectionMap[sid] }
            return step
        }
        let stepMap = Dictionary(uniqueKeysWithValues: steps.map { ($0.id, $0) })

        for ref in parsed.stepIngredientRefs {
            guard let step = stepMap[ref.stepId], let ing = ingredientMap[ref.ingredientId] else { continue }
            let refModel = StepIngredientRefModel(quantityDisplay: ref.quantityDisplay)
            context.insert(refModel); refModel.step = step; refModel.ingredient = ing
        }
    }

    // MARK: - Received recipes (Tab 2, Recipe Ownership Model v2)

    /// Cache a received recipe (from a shared_recipes mirror) into Room with isReceived = true.
    /// Preserves the canonical recipe id + child ids so refreshes REPLACE the same rows.
    /// Keeps the original author; the local copy is private + read-only.
    func cacheReceivedRecipe(_ shared: SharedRecipe, isImported: Bool, authorDisplayName: String?) throws {
        // Delete any existing local copy first so we overwrite in place
        if let existing = try fetchRecipe(id: shared.id) {
            context.delete(existing)
        }
        let now = Date()
        let recipe = RecipeModel(
            id: shared.id,
            title: shared.title,
            recipeDescription: shared.description,
            sourceUrls: shared.sourceUrls,
            baseServings: shared.baseServings,
            baseServingsMin: shared.baseServingsMin,
            baseServingsMax: shared.baseServingsMax,
            scaleIngredientId: shared.scaleIngredientId,
            scaleStep: shared.scaleStep,
            prepTimeMinutes: shared.prepTimeMinutes,
            cookTimeMinutes: shared.cookTimeMinutes,
            imageUrl: shared.imageUrl,
            tags: shared.tags,
            isCustomized: false,
            isImported: isImported,
            isReceived: true,
            needsReview: false,
            createdAt: now,
            updatedAt: now,
            authorId: shared.authorId,
            authorDisplayName: authorDisplayName,
            visibility: "private"
        )
        context.insert(recipe)

        let sections = shared.sections.sorted { $0.orderIndex < $1.orderIndex }.map { s -> RecipeSectionModel in
            let m = RecipeSectionModel(id: s.id, name: s.name, orderIndex: s.orderIndex)
            context.insert(m); m.recipe = recipe; return m
        }
        let sectionMap = Dictionary(uniqueKeysWithValues: sections.map { ($0.id, $0) })

        let ingredients = shared.ingredients.sorted { $0.orderIndex < $1.orderIndex }.map { i -> IngredientModel in
            let m = IngredientModel(
                id: i.id, name: i.name,
                quantityValue: i.quantityValue, quantityUnit: i.quantityUnit, quantityDisplay: i.quantityDisplay,
                groupLabel: i.groupLabel, isOptional: i.isOptional, orderIndex: i.orderIndex,
                quantityValueMetric: i.quantityValueMetric, quantityUnitMetric: i.quantityUnitMetric,
                quantityDisplayMetric: i.quantityDisplayMetric,
                quantityValueImperial: i.quantityValueImperial, quantityUnitImperial: i.quantityUnitImperial,
                quantityDisplayImperial: i.quantityDisplayImperial,
                shoppingNote: i.shoppingNote,
                quantityValueMax: i.quantityValueMax,
                quantityValueMaxMetric: i.quantityValueMaxMetric,
                quantityValueMaxImperial: i.quantityValueMaxImperial
            )
            context.insert(m); m.recipe = recipe
            if let sid = i.sectionId { m.section = sectionMap[sid] }
            return m
        }
        let ingMap = Dictionary(uniqueKeysWithValues: ingredients.map { ($0.id, $0) })

        let steps = shared.steps.sorted { $0.orderIndex < $1.orderIndex }.map { s -> StepModel in
            let m = StepModel(id: s.id, instruction: s.instruction, orderIndex: s.orderIndex)
            context.insert(m); m.recipe = recipe
            if let sid = s.sectionId { m.section = sectionMap[sid] }
            return m
        }
        let stepMap = Dictionary(uniqueKeysWithValues: steps.map { ($0.id, $0) })

        for step in shared.steps {
            for ref in step.ingredientRefs {
                guard let s = stepMap[step.id], let ing = ingMap[ref.ingredientId] else { continue }
                let refModel = StepIngredientRefModel(quantityDisplay: ref.quantityDisplay)
                context.insert(refModel); refModel.step = s; refModel.ingredient = ing
            }
        }
        try context.save()
    }

    /// Remove a received recipe's local cache (the cloud reference is removed separately).
    func removeReceivedRecipe(id: String) throws {
        try deleteRecipe(id: id)
    }

    // MARK: - Recipe variations (F10)

    /// Deep-copies a recipe into a new variation with fresh ids. Every internal reference
    /// (section/ingredient/step ids, step→ingredient refs, the scale anchor, substitute-group
    /// ids) is remapped so the copy is fully self-contained. Points at the *base* (the source's
    /// parent, or the source itself) so variations-of-variations still group under one base.
    /// Returns the new recipe id, or nil if the source can't be loaded.
    @discardableResult
    func duplicateAsVariant(sourceId: String, variantName: String, currentUid: String?, displayName: String?) throws -> String? {
        guard let source = try fetchRecipe(id: sourceId) else { return nil }
        let baseId = source.parentRecipeId ?? source.id
        let now = Date()
        let newId = "recipe-var-\(UUID().uuidString)"

        // ID remap tables
        let sectionIdMap = Dictionary(uniqueKeysWithValues: source.sections.map { ($0.id, "sec-\(UUID().uuidString)") })
        let ingredientIdMap = Dictionary(uniqueKeysWithValues: source.ingredients.map { ($0.id, "ing-\(UUID().uuidString)") })
        let stepIdMap = Dictionary(uniqueKeysWithValues: source.steps.map { ($0.id, "step-\(UUID().uuidString)") })
        let subGroupIds = Set(source.ingredients.compactMap { $0.substituteGroupId })
        let subGroupMap = Dictionary(uniqueKeysWithValues: subGroupIds.map { ($0, "subg-\(UUID().uuidString)") })

        let variant = RecipeModel(
            id: newId,
            title: source.title,
            recipeDescription: source.recipeDescription,
            sourceUrls: source.sourceUrls,
            baseServings: source.baseServings,
            baseServingsMin: source.baseServingsMin,
            baseServingsMax: source.baseServingsMax,
            scaleIngredientId: source.scaleIngredientId.flatMap { ingredientIdMap[$0] },
            scaleStep: source.scaleStep,
            prepTimeMinutes: source.prepTimeMinutes,
            cookTimeMinutes: source.cookTimeMinutes,
            imageUrl: source.imageUrl,
            tags: source.tags,
            isCustomized: true,
            isImported: source.isImported,
            isReceived: false,
            needsReview: false,
            version: 1,
            createdAt: now,
            updatedAt: now,
            authorId: currentUid ?? source.authorId,
            authorDisplayName: displayName ?? source.authorDisplayName,
            visibility: "private",
            parentRecipeId: baseId,
            variantName: variantName
        )
        context.insert(variant)

        let newSections = source.sections.sorted { $0.orderIndex < $1.orderIndex }.map { s -> RecipeSectionModel in
            let m = RecipeSectionModel(id: sectionIdMap[s.id]!, name: s.name, orderIndex: s.orderIndex)
            context.insert(m); m.recipe = variant; return m
        }
        let sectionMap = Dictionary(uniqueKeysWithValues: newSections.map { ($0.id, $0) })

        let newIngredients = source.ingredients.sorted { $0.orderIndex < $1.orderIndex }.map { i -> IngredientModel in
            let m = IngredientModel(
                id: ingredientIdMap[i.id]!, name: i.name,
                quantityValue: i.quantityValue, quantityUnit: i.quantityUnit, quantityDisplay: i.quantityDisplay,
                groupLabel: i.groupLabel, isOptional: i.isOptional,
                substituteGroupId: i.substituteGroupId.flatMap { subGroupMap[$0] },
                substituteRatio: i.substituteRatio, orderIndex: i.orderIndex,
                quantityValueMetric: i.quantityValueMetric, quantityUnitMetric: i.quantityUnitMetric,
                quantityDisplayMetric: i.quantityDisplayMetric,
                quantityValueImperial: i.quantityValueImperial, quantityUnitImperial: i.quantityUnitImperial,
                quantityDisplayImperial: i.quantityDisplayImperial,
                shoppingNote: i.shoppingNote,
                quantityValueMax: i.quantityValueMax,
                quantityValueMaxMetric: i.quantityValueMaxMetric,
                quantityValueMaxImperial: i.quantityValueMaxImperial
            )
            context.insert(m); m.recipe = variant
            if let sid = i.section?.id { m.section = sectionMap[sectionIdMap[sid]!] }
            return m
        }
        let ingMap = Dictionary(uniqueKeysWithValues: newIngredients.map { ($0.id, $0) })

        let newSteps = source.steps.sorted { $0.orderIndex < $1.orderIndex }.map { st -> StepModel in
            let m = StepModel(id: stepIdMap[st.id]!, instruction: st.instruction, orderIndex: st.orderIndex)
            context.insert(m); m.recipe = variant
            if let sid = st.section?.id { m.section = sectionMap[sectionIdMap[sid]!] }
            return m
        }
        let stepMap = Dictionary(uniqueKeysWithValues: newSteps.map { ($0.id, $0) })

        for st in source.steps {
            for ref in st.ingredientRefs {
                guard let oldIngId = ref.ingredient?.id,
                      let newIng = ingMap[ingredientIdMap[oldIngId] ?? ""],
                      let newStep = stepMap[stepIdMap[st.id] ?? ""] else { continue }
                let refModel = StepIngredientRefModel(quantityDisplay: ref.quantityDisplay)
                context.insert(refModel); refModel.step = newStep; refModel.ingredient = newIng
            }
        }

        try context.save()
        return newId
    }

    // MARK: - Update

    /// Atomically saves editor changes: deletes removed items, upserts survivors.
    func updateFullRecipe(
        recipeId: String,
        title: String,
        recipeDescription: String?,
        sourceUrls: [String],
        baseServings: Int,
        baseServingsMin: Int?,
        baseServingsMax: Int?,
        prepTimeMinutes: Int?,
        cookTimeMinutes: Int?,
        tags: [String],
        isImported: Bool,
        authorDisplayName: String?,
        variantName: String? = nil,
        sections: [EditorSection],
        deletedSectionIds: [String],
        deletedIngredientIds: [String],
        deletedStepIds: [String]
    ) throws {
        guard let recipe = try fetchRecipe(id: recipeId) else { return }

        // Delete removed items
        for stepId in deletedStepIds {
            if let step = recipe.steps.first(where: { $0.id == stepId }) {
                // delete refs
                for ref in step.ingredientRefs { context.delete(ref) }
                context.delete(step)
            }
        }
        for ingId in deletedIngredientIds {
            if let ing = recipe.ingredients.first(where: { $0.id == ingId }) {
                for ref in ing.stepRefs { context.delete(ref) }
                context.delete(ing)
            }
        }
        for secId in deletedSectionIds {
            if let sec = recipe.sections.first(where: { $0.id == secId }) {
                context.delete(sec)
            }
        }

        // Update recipe metadata
        recipe.title = title
        recipe.recipeDescription = recipeDescription
        recipe.sourceUrlsJson = encodeJSON(sourceUrls)
        recipe.baseServings = baseServings
        recipe.baseServingsMin = baseServingsMin
        recipe.baseServingsMax = baseServingsMax
        recipe.prepTimeMinutes = prepTimeMinutes
        recipe.cookTimeMinutes = cookTimeMinutes
        recipe.tagsJson = encodeJSON(tags)
        recipe.isImported = isImported
        recipe.isCustomized = true
        recipe.authorDisplayName = authorDisplayName
        // F10: only variations carry a variantName; bases keep nil. parentRecipeId is preserved.
        if recipe.parentRecipeId != nil { recipe.variantName = variantName }
        recipe.version += 1
        recipe.updatedAt = Date()

        // Upsert sections
        let existingSections = recipe.sections
        for (idx, edSec) in sections.enumerated() {
            if let existing = existingSections.first(where: { $0.id == edSec.id }) {
                existing.name = edSec.name
                existing.orderIndex = idx
            } else {
                let sec = RecipeSectionModel(id: edSec.id, name: edSec.name, orderIndex: idx)
                context.insert(sec)
                sec.recipe = recipe
            }
        }

        // Build section map after upsert
        let sectionMap = Dictionary(uniqueKeysWithValues: recipe.sections.map { ($0.id, $0) })

        // Upsert ingredients
        let existingIngredients = recipe.ingredients
        for edSec in sections {
            let section = sectionMap[edSec.id]
            for (idx, edIng) in edSec.ingredients.enumerated() {
                if let existing = existingIngredients.first(where: { $0.id == edIng.id }) {
                    existing.name = edIng.name
                    existing.quantityDisplay = edIng.quantityDisplay.isEmpty ? nil : edIng.quantityDisplay
                    existing.quantityUnit = edIng.quantityUnit.isEmpty ? nil : edIng.quantityUnit
                    existing.quantityValue = edIng.quantityValue
                    existing.groupLabel = edIng.groupLabel.isEmpty ? nil : edIng.groupLabel
                    existing.isOptional = edIng.isOptional
                    existing.orderIndex = idx
                    existing.section = section
                    // F6 conversion fields (preserved / updated by the convert button)
                    existing.quantityValueMetric = edIng.quantityValueMetric
                    existing.quantityUnitMetric = edIng.quantityUnitMetric
                    existing.quantityDisplayMetric = edIng.quantityDisplayMetric
                    existing.quantityValueImperial = edIng.quantityValueImperial
                    existing.quantityUnitImperial = edIng.quantityUnitImperial
                    existing.quantityDisplayImperial = edIng.quantityDisplayImperial
                    existing.shoppingNote = edIng.shoppingNote.trimmed.isEmpty ? nil : edIng.shoppingNote.trimmed
                    existing.quantityValueMax = edIng.quantityValueMax
                    existing.quantityValueMaxMetric = edIng.quantityValueMaxMetric
                    existing.quantityValueMaxImperial = edIng.quantityValueMaxImperial
                    existing.substituteGroupId = edIng.substituteGroupId
                } else {
                    let ing = IngredientModel(
                        id: edIng.id,
                        name: edIng.name,
                        quantityValue: edIng.quantityValue,
                        quantityUnit: edIng.quantityUnit.isEmpty ? nil : edIng.quantityUnit,
                        quantityDisplay: edIng.quantityDisplay.isEmpty ? nil : edIng.quantityDisplay,
                        groupLabel: edIng.groupLabel.isEmpty ? nil : edIng.groupLabel,
                        isOptional: edIng.isOptional,
                        substituteGroupId: edIng.substituteGroupId,
                        orderIndex: idx,
                        quantityValueMetric: edIng.quantityValueMetric,
                        quantityUnitMetric: edIng.quantityUnitMetric,
                        quantityDisplayMetric: edIng.quantityDisplayMetric,
                        quantityValueImperial: edIng.quantityValueImperial,
                        quantityUnitImperial: edIng.quantityUnitImperial,
                        quantityDisplayImperial: edIng.quantityDisplayImperial,
                        shoppingNote: edIng.shoppingNote.trimmed.isEmpty ? nil : edIng.shoppingNote.trimmed,
                        quantityValueMax: edIng.quantityValueMax,
                        quantityValueMaxMetric: edIng.quantityValueMaxMetric,
                        quantityValueMaxImperial: edIng.quantityValueMaxImperial
                    )
                    context.insert(ing)
                    ing.recipe = recipe
                    ing.section = section
                }
            }
        }

        // Upsert steps
        let existingSteps = recipe.steps
        for edSec in sections {
            let section = sectionMap[edSec.id]
            for (idx, edStep) in edSec.steps.enumerated() {
                if let existing = existingSteps.first(where: { $0.id == edStep.id }) {
                    existing.instruction = edStep.instruction
                    existing.orderIndex = idx
                    existing.section = section
                } else {
                    let step = StepModel(id: edStep.id, instruction: edStep.instruction, orderIndex: idx)
                    context.insert(step)
                    step.recipe = recipe
                    step.section = section
                }
            }
        }

        // F4/F16: rewrite step→ingredient refs from the draft's EditorStep.ingredientIds.
        // Clear all existing refs, then recreate from the draft so additions/removals persist.
        let stepMap = Dictionary(uniqueKeysWithValues: recipe.steps.map { ($0.id, $0) })
        let ingMap = Dictionary(uniqueKeysWithValues: recipe.ingredients.map { ($0.id, $0) })
        for step in recipe.steps {
            for ref in step.ingredientRefs { context.delete(ref) }
        }
        for edSec in sections {
            for edStep in edSec.steps {
                guard let step = stepMap[edStep.id] else { continue }
                for ingId in edStep.ingredientIds {
                    guard let ing = ingMap[ingId] else { continue }
                    let refModel = StepIngredientRefModel(quantityDisplay: ing.quantityDisplay)
                    context.insert(refModel)
                    refModel.step = step
                    refModel.ingredient = ing
                }
            }
        }

        try context.save()
    }

    func upsertFromFirestore(data: [String: Any]) throws {
        guard let id = data["id"] as? String else { return }

        // Try to parse a full recipe with sections/ingredients/steps from Firestore
        let now = Date()

        if let existing = try fetchRecipe(id: id) {
            // Update metadata only — don't overwrite local content if newer
            let firestoreUpdatedAt: Date
            if let ts = data["updatedAt"] as? Double {
                firestoreUpdatedAt = Date(timeIntervalSince1970: ts / 1000)
            } else {
                firestoreUpdatedAt = now
            }
            guard firestoreUpdatedAt > existing.updatedAt else { return }

            if let v = data["title"] as? String { existing.title = v }
            if let v = data["description"] as? String { existing.recipeDescription = v }
            if let v = data["baseServings"] as? Int { existing.baseServings = v }
            // Metadata the block below previously skipped — otherwise cross-device edits to these
            // (and anchor-scaling) silently revert on the next pull.
            existing.baseServingsMin = data["baseServingsMin"] as? Int
            existing.baseServingsMax = data["baseServingsMax"] as? Int
            existing.prepTimeMinutes = data["prepTimeMinutes"] as? Int
            existing.cookTimeMinutes = data["cookTimeMinutes"] as? Int
            existing.imageUrl = data["imageUrl"] as? String
            existing.scaleIngredientId = data["scaleIngredientId"] as? String
            if let v = data["scaleStep"] as? Double { existing.scaleStep = v }
            if let v = data["authorId"] as? String { existing.authorId = v }
            if let v = data["authorDisplayName"] as? String { existing.authorDisplayName = v }
            if let v = data["isCustomized"] as? Bool { existing.isCustomized = v }
            if let v = data["isImported"] as? Bool { existing.isImported = v }
            if let v = data["version"] as? Int { existing.version = v }
            if let v = data["visibility"] as? String { existing.visibility = v }
            existing.sharedWith = (data["sharedWith"] as? [String]) ?? []   // F17
            existing.parentRecipeId = data["parentRecipeId"] as? String   // F10: preserve grouping
            existing.variantName = data["variantName"] as? String
            // Refresh tags / source URLs (the metadata block above doesn't cover them).
            if let v = data["tags"] as? [String], let j = try? JSONEncoder().encode(v),
               let s = String(data: j, encoding: .utf8) { existing.tagsJson = s }
            if let v = data["sourceUrls"] as? [String], let j = try? JSONEncoder().encode(v),
               let s = String(data: j, encoding: .utf8) { existing.sourceUrlsJson = s }
            existing.updatedAt = firestoreUpdatedAt
            existing.syncedAt = now
            // Clean-replace the synced content so cross-device ingredient/step edits actually
            // propagate to an EXISTING local recipe (previously only metadata was updated) and
            // removed children don't linger as orphans. Preserves local notes + shopping checks.
            try replaceSyncedContent(of: existing, from: data)
        } else {
            // Full insert from Firestore
            let parsed = try firestoreToParsedRecipeData(id: id, data: data)
            let authorId = data["authorId"] as? String
            let authorName = data["authorDisplayName"] as? String
            let isImported = data["isImported"] as? Bool ?? false
            let visibility = data["visibility"] as? String ?? "private"
            let recipe = try insertFullRecipeFromParsed(
                recipeId: id,
                parsed: parsed,
                authorId: authorId,
                authorDisplayName: authorName,
                isImported: isImported,
                needsReview: false,
                visibility: visibility
            )
            // F10: preserve variation grouping on multi-device personal pull
            recipe.parentRecipeId = data["parentRecipeId"] as? String
            recipe.variantName = data["variantName"] as? String
            recipe.sharedWith = (data["sharedWith"] as? [String]) ?? []   // F17
            try context.save()
            return
        }
        try context.save()
    }

    func confirmReview(recipeId: String) throws {
        guard let recipe = try fetchRecipe(id: recipeId) else { return }
        recipe.needsReview = false
        recipe.updatedAt = Date()
        try context.save()
    }

    func updateIsImported(recipeId: String, isImported: Bool) throws {
        guard let recipe = try fetchRecipe(id: recipeId) else { return }
        recipe.isImported = isImported
        recipe.authorDisplayName = isImported ? "Imported" : nil
        recipe.updatedAt = Date()
        try context.save()
    }

    func updateVisibility(recipeId: String, visibility: String) throws {
        guard let recipe = try fetchRecipe(id: recipeId) else { return }
        recipe.visibility = visibility
        if visibility == "private" { recipe.sharedWith = [] }   // F17: clear ACL when going private
        recipe.updatedAt = Date()
        try context.save()
    }

    /// F17: set the "shared" tier with an explicit recipient list (per-recipient ACL).
    func updateSharedRecipients(recipeId: String, sharedWith: [String]) throws {
        guard let recipe = try fetchRecipe(id: recipeId) else { return }
        recipe.visibility = sharedWith.isEmpty ? "private" : "shared"
        recipe.sharedWith = sharedWith
        recipe.updatedAt = Date()
        try context.save()
    }

    func deleteRecipe(id: String) throws {
        guard let recipe = try fetchRecipe(id: id) else { return }
        context.delete(recipe)
        try clearShoppingChecks(recipeId: id)   // F11: cascade — checks are per-recipe
        try context.save()
    }

    func deleteAllRecipes() throws {
        let all = try fetchAllRecipes()
        for recipe in all { context.delete(recipe) }
        try context.delete(model: ShoppingCheckModel.self)
        try context.delete(model: CookedLogModel.self)
        try context.save()
    }

    // MARK: - Cooked log (F13 — local only, never synced)

    /// Record that a recipe was cooked now (Cooking Mode "Done"). Upserts the row.
    func markCooked(recipeId: String) throws {
        let descriptor = FetchDescriptor<CookedLogModel>(predicate: #Predicate { $0.recipeId == recipeId })
        if let existing = try context.fetch(descriptor).first {
            existing.cookedAt = Date()
        } else {
            context.insert(CookedLogModel(recipeId: recipeId))
        }
        try context.save()
    }

    /// recipeId → last-cooked Date. Drives the Discover recency penalty + "Recently cooked" shelf.
    func cookedLog() throws -> [String: Date] {
        let rows = try context.fetch(FetchDescriptor<CookedLogModel>())
        return Dictionary(rows.map { ($0.recipeId, $0.cookedAt) }, uniquingKeysWith: { a, _ in a })
    }

    // MARK: - Shopping list checked items (F11 — local only, never synced)

    func checkedShoppingItems(recipeId: String) throws -> Set<String> {
        let descriptor = FetchDescriptor<ShoppingCheckModel>(
            predicate: #Predicate { $0.recipeId == recipeId }
        )
        return Set(try context.fetch(descriptor).map { $0.itemKey })
    }

    func setShoppingChecked(recipeId: String, itemKey: String, checked: Bool) throws {
        let descriptor = FetchDescriptor<ShoppingCheckModel>(
            predicate: #Predicate { $0.recipeId == recipeId && $0.itemKey == itemKey }
        )
        let existing = try context.fetch(descriptor)
        if checked {
            if existing.isEmpty { context.insert(ShoppingCheckModel(recipeId: recipeId, itemKey: itemKey)) }
        } else {
            for row in existing { context.delete(row) }
        }
        try context.save()
    }

    func clearShoppingChecks(recipeId: String) throws {
        let descriptor = FetchDescriptor<ShoppingCheckModel>(
            predicate: #Predicate { $0.recipeId == recipeId }
        )
        for row in try context.fetch(descriptor) { context.delete(row) }
        try context.save()
    }

    func saveRecipe(_ recipe: RecipeModel) throws {
        recipe.updatedAt = Date()
        try context.save()
    }

    // MARK: - Private helpers

    private func encodeJSON<T: Encodable>(_ value: T) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let str = String(data: data, encoding: .utf8) else { return "[]" }
        return str
    }

    private func firestoreToParsedRecipeData(id: String, data: [String: Any]) throws -> ParsedRecipeData {
        let sectionsRaw = data["sections"] as? [[String: Any]] ?? []
        let sections = sectionsRaw.compactMap { s -> ParsedSection? in
            guard let sid = s["id"] as? String, let name = s["name"] as? String else { return nil }
            return ParsedSection(id: sid, name: name, orderIndex: s["orderIndex"] as? Int ?? 0)
        }
        let ingredientsRaw = data["ingredients"] as? [[String: Any]] ?? []
        let ingredients = ingredientsRaw.compactMap { i -> ParsedIngredient? in
            guard let iid = i["id"] as? String, let name = i["name"] as? String else { return nil }
            return ParsedIngredient(
                id: iid,
                sectionId: i["sectionId"] as? String,
                name: name,
                quantityValue: (i["quantityValue"] as? NSNumber)?.doubleValue,
                quantityUnit: i["quantityUnit"] as? String,
                quantityDisplay: i["quantityDisplay"] as? String,
                groupLabel: i["groupLabel"] as? String,
                isOptional: i["isOptional"] as? Bool ?? false,
                orderIndex: i["orderIndex"] as? Int ?? 0,
                quantityValueMetric: (i["quantityValueMetric"] as? NSNumber)?.doubleValue,
                quantityUnitMetric: i["quantityUnitMetric"] as? String,
                quantityDisplayMetric: i["quantityDisplayMetric"] as? String,
                quantityValueImperial: (i["quantityValueImperial"] as? NSNumber)?.doubleValue,
                quantityUnitImperial: i["quantityUnitImperial"] as? String,
                quantityDisplayImperial: i["quantityDisplayImperial"] as? String,
                shoppingNote: i["shoppingNote"] as? String,
                quantityValueMax: (i["quantityValueMax"] as? NSNumber)?.doubleValue,
                quantityValueMaxMetric: (i["quantityValueMaxMetric"] as? NSNumber)?.doubleValue,
                quantityValueMaxImperial: (i["quantityValueMaxImperial"] as? NSNumber)?.doubleValue
            )
        }
        let stepsRaw = data["steps"] as? [[String: Any]] ?? []
        let steps = stepsRaw.compactMap { s -> ParsedStep? in
            guard let sid = s["id"] as? String, let instruction = s["instruction"] as? String else { return nil }
            return ParsedStep(id: sid, sectionId: s["sectionId"] as? String, instruction: instruction, orderIndex: s["orderIndex"] as? Int ?? 0)
        }
        let refsRaw = data["stepIngredientRefs"] as? [[String: Any]] ?? []
        let refs = refsRaw.compactMap { r -> ParsedStepRef? in
            guard let stepId = r["stepId"] as? String, let ingId = r["ingredientId"] as? String else { return nil }
            return ParsedStepRef(stepId: stepId, ingredientId: ingId, quantityDisplay: r["quantityDisplay"] as? String)
        }
        let sourceUrls: [String]
        if let urls = data["sourceUrls"] as? [String] { sourceUrls = urls }
        else { sourceUrls = [] }
        return ParsedRecipeData(
            title: data["title"] as? String ?? "",
            description: data["description"] as? String,
            sourceUrls: sourceUrls,
            baseServings: data["baseServings"] as? Int ?? 1,
            baseServingsMin: data["baseServingsMin"] as? Int,
            baseServingsMax: data["baseServingsMax"] as? Int,
            prepTimeMinutes: data["prepTimeMinutes"] as? Int,
            cookTimeMinutes: data["cookTimeMinutes"] as? Int,
            imageUrl: data["imageUrl"] as? String,
            tags: data["tags"] as? [String] ?? [],
            sections: sections,
            ingredients: ingredients,
            steps: steps,
            stepIngredientRefs: refs,
            parseNotes: nil,
            scaleIngredientId: data["scaleIngredientId"] as? String,
            scaleStep: (data["scaleStep"] as? Double) ?? 1.0
        )
    }
}
