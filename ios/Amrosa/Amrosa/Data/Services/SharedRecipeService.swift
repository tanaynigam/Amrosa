import FirebaseFirestore
import Foundation

/// Manages the `shared_recipes` Firestore collection.
/// shared_recipes/{recipeId}               — public recipe mirror
///   /comments/{commentId}                 — community comments
@MainActor
final class SharedRecipeService {
    private let db = Firestore.firestore()
    private let authRepository: AuthRepository
    private let repository: RecipeRepository

    private let sharedCollection = "shared_recipes"
    private let commentsCollection = "comments"

    init(authRepository: AuthRepository, repository: RecipeRepository) {
        self.authRepository = authRepository
        self.repository = repository
    }

    // MARK: - Publish / Unpublish

    /// Mirror a recipe into shared_recipes/{recipeId}.
    func publish(_ recipe: RecipeModel) async -> Bool {
        do {
            let doc = buildDocument(recipe)
            try await db.collection(sharedCollection).document(recipe.id).setData(doc, merge: true)
            return true
        } catch {
            return false
        }
    }

    /// Remove a recipe from shared_recipes.
    func unpublish(_ recipeId: String) async -> Bool {
        do {
            try await db.collection(sharedCollection).document(recipeId).delete()
            return true
        } catch {
            return false
        }
    }

    // MARK: - Browse

    /// Live stream of all public shared recipes via AsyncStream.
    func sharedRecipesStream() -> AsyncStream<[SharedRecipe]> {
        AsyncStream { continuation in
            let listener = self.db.collection(self.sharedCollection)
                .addSnapshotListener { snapshot, error in
                    guard let docs = snapshot?.documents else {
                        continuation.yield([])
                        return
                    }
                    let recipes = docs.compactMap { doc -> SharedRecipe? in
                        self.parseSharedRecipe(id: doc.documentID, data: doc.data())
                    }
                    continuation.yield(recipes)
                }
            continuation.onTermination = { _ in listener.remove() }
        }
    }

    /// One-shot fetch of a single shared recipe (full detail).
    func getSharedRecipeDetail(recipeId: String) async -> SharedRecipe? {
        guard let doc = try? await db.collection(sharedCollection).document(recipeId).getDocument() else { return nil }
        guard let data = doc.data() else { return nil }
        return parseSharedRecipe(id: doc.documentID, data: data)
    }

    // MARK: - Comments

    func commentsStream(recipeId: String) -> AsyncStream<[SharedComment]> {
        AsyncStream { continuation in
            let listener = self.db.collection(self.sharedCollection)
                .document(recipeId)
                .collection(self.commentsCollection)
                .order(by: "createdAt")
                .addSnapshotListener { snapshot, _ in
                    let comments = snapshot?.documents.compactMap { doc -> SharedComment? in
                        guard let authorId = doc["authorId"] as? String,
                              let authorName = doc["authorDisplayName"] as? String,
                              let content = doc["content"] as? String,
                              let createdAt = doc["createdAt"] as? Double else { return nil }
                        return SharedComment(
                            id: doc.documentID,
                            recipeId: recipeId,
                            authorId: authorId,
                            authorDisplayName: authorName,
                            content: content,
                            createdAt: Date(timeIntervalSince1970: createdAt / 1000)
                        )
                    } ?? []
                    continuation.yield(comments)
                }
            continuation.onTermination = { _ in listener.remove() }
        }
    }

    func addComment(recipeId: String, content: String) async -> Bool {
        guard let uid = authRepository.uid,
              let displayName = authRepository.displayName ?? authRepository.email,
              !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        let id = UUID().uuidString
        let doc: [String: Any] = [
            "id": id,
            "authorId": uid,
            "authorDisplayName": displayName,
            "content": content.trimmingCharacters(in: .whitespacesAndNewlines),
            "createdAt": Date().timeIntervalSince1970 * 1000
        ]
        do {
            try await db.collection(sharedCollection).document(recipeId)
                .collection(commentsCollection).document(id).setData(doc)
            return true
        } catch {
            return false
        }
    }

    func deleteComment(recipeId: String, commentId: String) async -> Bool {
        do {
            try await db.collection(sharedCollection).document(recipeId)
                .collection(commentsCollection).document(commentId).delete()
            return true
        } catch {
            return false
        }
    }

    // MARK: - Copy to My Recipes

    func copyToMyRecipes(_ sharedRecipe: SharedRecipe) async -> Bool {
        guard let uid = authRepository.uid else { return false }
        let newId = UUID().uuidString
        let now = Date()

        // Build ParsedRecipeData from the shared recipe
        let sections = sharedRecipe.sections.map { s in
            ParsedSection(id: UUID().uuidString, name: s.name, orderIndex: s.orderIndex)
        }
        let sectionIdMap = Dictionary(uniqueKeysWithValues: zip(sharedRecipe.sections.map(\.id), sections.map(\.id)))

        let ingredients = sharedRecipe.ingredients.map { ing in
            ParsedIngredient(
                id: UUID().uuidString,
                sectionId: ing.sectionId.flatMap { sectionIdMap[$0] },
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
        let ingIdMap = Dictionary(uniqueKeysWithValues: zip(sharedRecipe.ingredients.map(\.id), ingredients.map(\.id)))

        let steps = sharedRecipe.steps.map { step in
            ParsedStep(
                id: UUID().uuidString,
                sectionId: step.sectionId.flatMap { sectionIdMap[$0] },
                instruction: step.instruction,
                orderIndex: step.orderIndex
            )
        }
        let stepIdMap = Dictionary(uniqueKeysWithValues: zip(sharedRecipe.steps.map(\.id), steps.map(\.id)))

        let refs = sharedRecipe.steps.flatMap { step -> [ParsedStepRef] in
            step.ingredientRefs.compactMap { ref in
                guard let newStepId = stepIdMap[step.id],
                      let newIngId = ingIdMap[ref.ingredientId] else { return nil }
                return ParsedStepRef(stepId: newStepId, ingredientId: newIngId, quantityDisplay: ref.quantityDisplay)
            }
        }

        let parsed = ParsedRecipeData(
            title: sharedRecipe.title,
            description: sharedRecipe.description,
            sourceUrls: sharedRecipe.sourceUrls,
            baseServings: sharedRecipe.baseServings,
            baseServingsMin: sharedRecipe.baseServingsMin,
            baseServingsMax: sharedRecipe.baseServingsMax,
            prepTimeMinutes: sharedRecipe.prepTimeMinutes,
            cookTimeMinutes: sharedRecipe.cookTimeMinutes,
            imageUrl: sharedRecipe.imageUrl,
            tags: sharedRecipe.tags,
            sections: sections,
            ingredients: ingredients,
            steps: steps,
            stepIngredientRefs: refs,
            parseNotes: nil
        )

        do {
            _ = try repository.insertFullRecipeFromParsed(
                recipeId: newId,
                parsed: parsed,
                authorId: uid,
                authorDisplayName: authRepository.displayName ?? authRepository.email ?? "Me",
                isImported: false,
                needsReview: false,
                visibility: "private"
            )
            return true
        } catch {
            return false
        }
    }

    // MARK: - Private helpers

    private func buildDocument(_ recipe: RecipeModel) -> [String: Any] {
        let sections = recipe.sections.sorted { $0.orderIndex < $1.orderIndex }.map { s -> [String: Any] in
            ["id": s.id, "name": s.name, "orderIndex": s.orderIndex]
        }
        let ingredients = recipe.ingredients.sorted { $0.orderIndex < $1.orderIndex }.map { ing -> [String: Any] in
            var d: [String: Any] = [
                "id": ing.id,
                "name": ing.name,
                "isOptional": ing.isOptional,
                "orderIndex": ing.orderIndex
            ]
            if let v = ing.section?.id         { d["sectionId"] = v }
            if let v = ing.quantityValue        { d["quantityValue"] = v }
            if let v = ing.quantityUnit         { d["quantityUnit"] = v }
            if let v = ing.quantityDisplay      { d["quantityDisplay"] = v }
            if let v = ing.groupLabel           { d["groupLabel"] = v }
            if let v = ing.substituteGroupId    { d["substituteGroupId"] = v }
            if let v = ing.quantityValueMetric    { d["quantityValueMetric"] = v }
            if let v = ing.quantityUnitMetric     { d["quantityUnitMetric"] = v }
            if let v = ing.quantityDisplayMetric  { d["quantityDisplayMetric"] = v }
            if let v = ing.quantityValueImperial   { d["quantityValueImperial"] = v }
            if let v = ing.quantityUnitImperial    { d["quantityUnitImperial"] = v }
            if let v = ing.quantityDisplayImperial { d["quantityDisplayImperial"] = v }
            return d
        }
        let steps = recipe.steps.sorted { $0.orderIndex < $1.orderIndex }.map { step -> [String: Any] in
            var d: [String: Any] = [
                "id": step.id,
                "instruction": step.instruction,
                "orderIndex": step.orderIndex
            ]
            if let v = step.section?.id { d["sectionId"] = v }
            return d
        }
        let refs = recipe.steps.flatMap { step in
            step.ingredientRefs.map { ref -> [String: Any] in
                var d: [String: Any] = ["stepId": step.id, "ingredientId": ref.ingredient?.id ?? ""]
                if let v = ref.quantityDisplay { d["quantityDisplay"] = v }
                return d
            }
        }

        var doc: [String: Any] = [
            "title": recipe.title,
            "baseServings": recipe.baseServings,
            "isCustomized": recipe.isCustomized,
            "isImported": recipe.isImported,
            "version": recipe.version,
            "visibility": "public",
            "sharedAt": Date().timeIntervalSince1970 * 1000,
            "updatedAt": recipe.updatedAt.timeIntervalSince1970 * 1000,
            "createdAt": recipe.createdAt.timeIntervalSince1970 * 1000,
            "authorId": recipe.authorId ?? "",
            "authorDisplayName": recipe.isImported ? "Imported" : (recipe.authorDisplayName ?? "User"),
            "tags": recipe.tags,
            "sourceUrls": recipe.sourceUrls,
            "sections": sections,
            "ingredients": ingredients,
            "steps": steps,
            "stepIngredientRefs": refs
        ]
        if let v = recipe.recipeDescription    { doc["description"] = v }
        if let v = recipe.prepTimeMinutes       { doc["prepTimeMinutes"] = v }
        if let v = recipe.cookTimeMinutes       { doc["cookTimeMinutes"] = v }
        if let v = recipe.imageUrl              { doc["imageUrl"] = v }
        if let v = recipe.baseServingsMin       { doc["baseServingsMin"] = v }
        if let v = recipe.baseServingsMax       { doc["baseServingsMax"] = v }
        if let v = recipe.scaleIngredientId     { doc["scaleIngredientId"] = v }
        return doc
    }

    private func parseSharedRecipe(id: String, data: [String: Any]) -> SharedRecipe? {
        guard let title = data["title"] as? String else { return nil }

        let sectionsRaw = data["sections"] as? [[String: Any]] ?? []
        let sections = sectionsRaw.compactMap { s -> SharedSection? in
            guard let sid = s["id"] as? String, let name = s["name"] as? String else { return nil }
            return SharedSection(id: sid, name: name, orderIndex: s["orderIndex"] as? Int ?? 0)
        }.sorted { $0.orderIndex < $1.orderIndex }

        let ingredientsRaw = data["ingredients"] as? [[String: Any]] ?? []
        let ingredients = ingredientsRaw.compactMap { i -> SharedIngredient? in
            guard let iid = i["id"] as? String, let name = i["name"] as? String else { return nil }
            return SharedIngredient(
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
        }.sorted { $0.orderIndex < $1.orderIndex }

        let refsRaw = data["stepIngredientRefs"] as? [[String: Any]] ?? []
        let refsByStep: [String: [SharedStepRef]] = Dictionary(grouping: refsRaw.compactMap { r -> (String, SharedStepRef)? in
            guard let stepId = r["stepId"] as? String, let ingId = r["ingredientId"] as? String else { return nil }
            return (stepId, SharedStepRef(ingredientId: ingId, quantityDisplay: r["quantityDisplay"] as? String))
        }, by: { $0.0 }).mapValues { $0.map(\.1) }

        let stepsRaw = data["steps"] as? [[String: Any]] ?? []
        let steps = stepsRaw.compactMap { s -> SharedStep? in
            guard let sid = s["id"] as? String, let instruction = s["instruction"] as? String else { return nil }
            return SharedStep(
                id: sid,
                sectionId: s["sectionId"] as? String,
                instruction: instruction,
                orderIndex: s["orderIndex"] as? Int ?? 0,
                ingredientRefs: refsByStep[sid] ?? []
            )
        }.sorted { $0.orderIndex < $1.orderIndex }

        let sourceUrls: [String]
        if let urls = data["sourceUrls"] as? [String] { sourceUrls = urls }
        else { sourceUrls = [] }

        let tags: [String]
        if let t = data["tags"] as? [String] { tags = t }
        else { tags = [] }

        return SharedRecipe(
            id: id,
            title: title,
            description: data["description"] as? String,
            sourceUrls: sourceUrls,
            baseServings: data["baseServings"] as? Int ?? 1,
            baseServingsMin: data["baseServingsMin"] as? Int,
            baseServingsMax: data["baseServingsMax"] as? Int,
            scaleIngredientId: data["scaleIngredientId"] as? String,
            scaleStep: (data["scaleStep"] as? NSNumber)?.doubleValue ?? 1.0,
            prepTimeMinutes: data["prepTimeMinutes"] as? Int,
            cookTimeMinutes: data["cookTimeMinutes"] as? Int,
            imageUrl: data["imageUrl"] as? String,
            tags: tags,
            authorId: data["authorId"] as? String,
            authorDisplayName: data["authorDisplayName"] as? String,
            visibility: data["visibility"] as? String ?? "public",
            sections: sections,
            ingredients: ingredients,
            steps: steps
        )
    }
}
