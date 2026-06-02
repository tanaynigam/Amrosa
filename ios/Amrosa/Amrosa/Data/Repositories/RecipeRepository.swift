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
    func fetchPersonalRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { !$0.isImported && !$0.isReceived },
            sortBy: [SortDescriptor(\.title)]
        )
        return try context.fetch(descriptor)
    }

    /// Tab 1 — my recipes only (excludes received references).
    func fetchMyRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { !$0.isReceived },
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]
        )
        return try context.fetch(descriptor)
    }

    /// Tab 2 — recipes received from other users (read-only references).
    func fetchReceivedRecipes() throws -> [RecipeModel] {
        let descriptor = FetchDescriptor<RecipeModel>(
            predicate: #Predicate { $0.isReceived },
            sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]
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
                quantityDisplayImperial: pi.quantityDisplayImperial
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
                quantityDisplayImperial: i.quantityDisplayImperial
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
                } else {
                    let ing = IngredientModel(
                        id: edIng.id,
                        name: edIng.name,
                        quantityValue: edIng.quantityValue,
                        quantityUnit: edIng.quantityUnit.isEmpty ? nil : edIng.quantityUnit,
                        quantityDisplay: edIng.quantityDisplay.isEmpty ? nil : edIng.quantityDisplay,
                        groupLabel: edIng.groupLabel.isEmpty ? nil : edIng.groupLabel,
                        isOptional: edIng.isOptional,
                        orderIndex: idx
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
            if let v = data["authorId"] as? String { existing.authorId = v }
            if let v = data["authorDisplayName"] as? String { existing.authorDisplayName = v }
            if let v = data["isCustomized"] as? Bool { existing.isCustomized = v }
            if let v = data["isImported"] as? Bool { existing.isImported = v }
            if let v = data["version"] as? Int { existing.version = v }
            if let v = data["visibility"] as? String { existing.visibility = v }
            existing.updatedAt = firestoreUpdatedAt
            existing.syncedAt = now
        } else {
            // Full insert from Firestore
            let parsed = try firestoreToParsedRecipeData(id: id, data: data)
            let authorId = data["authorId"] as? String
            let authorName = data["authorDisplayName"] as? String
            let isImported = data["isImported"] as? Bool ?? false
            let visibility = data["visibility"] as? String ?? "private"
            _ = try insertFullRecipeFromParsed(
                recipeId: id,
                parsed: parsed,
                authorId: authorId,
                authorDisplayName: authorName,
                isImported: isImported,
                needsReview: false,
                visibility: visibility
            )
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
        recipe.updatedAt = Date()
        try context.save()
    }

    func deleteRecipe(id: String) throws {
        guard let recipe = try fetchRecipe(id: id) else { return }
        context.delete(recipe)
        try context.save()
    }

    func deleteAllRecipes() throws {
        let all = try fetchAllRecipes()
        for recipe in all { context.delete(recipe) }
        try context.save()
    }

    func saveRecipe(_ recipe: RecipeModel) throws {
        recipe.updatedAt = Date()
        try context.save()
    }

    func addNote(to recipeId: String, content: String) throws {
        guard let recipe = try fetchRecipe(id: recipeId) else { return }
        let note = RecipeNoteModel(id: UUID().uuidString, content: content)
        context.insert(note)
        note.recipe = recipe
        try context.save()
    }

    func updateNote(_ note: RecipeNoteModel, content: String) throws {
        note.content = content
        note.updatedAt = Date()
        try context.save()
    }

    func deleteNote(_ note: RecipeNoteModel) throws {
        context.delete(note)
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
                quantityDisplayImperial: i["quantityDisplayImperial"] as? String
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
            parseNotes: nil
        )
    }
}
