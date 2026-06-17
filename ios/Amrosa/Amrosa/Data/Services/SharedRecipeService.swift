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
    // F18: Notes live in a dedicated top-level `recipe_notes/{recipeId}` collection (NOT the mirror)
    // so they're visibility-independent — a note added while private persists and becomes visible
    // to everyone once the recipe is shared. `recipe_notes/{id}` = {recipeAuthorId, locked}.
    private let notesCollection = "recipe_notes"
    private let notesSubcollection = "notes"

    private func notesDoc(_ recipeId: String) -> DocumentReference {
        db.collection(notesCollection).document(recipeId)
    }
    private func notesCol(_ recipeId: String) -> CollectionReference {
        notesDoc(recipeId).collection(notesSubcollection)
    }

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
    // MARK: - Discover feed (F13)

    /// Lightweight list of public recipes for the Discover feed. Newest first, capped.
    /// Returns `DiscoverRecipe` cards (no sections/ingredients) — full detail fetched on tap.
    func getPublicRecipeSummaries(limit: Int = 50) async -> [DiscoverRecipe] {
        guard let snapshot = try? await db.collection(sharedCollection)
            .whereField("visibility", isEqualTo: "public")
            .order(by: "sharedAt", descending: true)
            .limit(to: limit)
            .getDocuments() else { return [] }
        return snapshot.documents.compactMap { parsePublicSummary($0.documentID, $0.data()) }
    }

    /// Most-saved public recipes (the "Popular" shelf + popularity ranking).
    /// Requires composite index (visibility ==, saveCount desc).
    func getPopularPublicRecipes(limit: Int = 20) async -> [DiscoverRecipe] {
        guard let snapshot = try? await db.collection(sharedCollection)
            .whereField("visibility", isEqualTo: "public")
            .order(by: "saveCount", descending: true)
            .limit(to: limit)
            .getDocuments() else { return [] }
        return snapshot.documents.compactMap { parsePublicSummary($0.documentID, $0.data()) }
    }

    /// Search public recipes by a query word (F13 Phase 3a). Firestore has no full-text, so we match
    /// against the precomputed `searchTokens` array (title + tag words): query the longest token via
    /// array-contains, then refine client-side to the full query so multi-word searches narrow.
    /// Requires index (visibility ==, searchTokens array-contains).
    func searchPublicRecipes(query: String, limit: Int = 25) async -> [DiscoverRecipe] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard q.count >= 2 else { return [] }
        let words = q.split { !$0.isLetter && !$0.isNumber }.map(String.init).filter { $0.count >= 2 }
        guard let token = words.max(by: { $0.count < $1.count }) else { return [] }
        guard let snapshot = try? await db.collection(sharedCollection)
            .whereField("visibility", isEqualTo: "public")
            .whereField("searchTokens", arrayContains: token)
            .limit(to: limit)
            .getDocuments() else { return [] }
        return snapshot.documents
            .compactMap { parsePublicSummary($0.documentID, $0.data()) }
            // Refine to the full query (substring on title or any tag).
            .filter { r in
                r.title.lowercased().contains(q) || r.tags.contains { $0.lowercased().contains(q) }
            }
    }

    private func parsePublicSummary(_ id: String, _ data: [String: Any]) -> DiscoverRecipe? {
        guard let title = data["title"] as? String else { return nil }
        return DiscoverRecipe(
            recipeId: id,
            title: title,
            tags: (data["tags"] as? [String]) ?? [],
            prepTimeMinutes: data["prepTimeMinutes"] as? Int,
            cookTimeMinutes: data["cookTimeMinutes"] as? Int,
            source: .public,
            authorUid: data["authorId"] as? String,
            authorName: data["authorDisplayName"] as? String,
            isLocal: false,
            saveCount: data["saveCount"] as? Int ?? 0,
            likeCount: data["likeCount"] as? Int ?? 0
        )
    }

    // MARK: - Likes (F13 Phase 2)

    /// Whether the current user liked a recipe + the like/save counts (counters are
    /// Cloud-Function-maintained on the mirror doc).
    struct LikeState: Equatable {
        var isLiked: Bool = false
        var likeCount: Int = 0
        var saveCount: Int = 0
    }

    /// Like / unlike a shared recipe as the current user (counter maintained by a Cloud Function).
    func setLiked(recipeId: String, liked: Bool) async {
        guard let uid = authRepository.uid else { return }
        let ref = db.collection(sharedCollection).document(recipeId).collection("likes").document(uid)
        if liked {
            try? await ref.setData(["likedAt": Date().timeIntervalSince1970 * 1000])
        } else {
            try? await ref.delete()
        }
    }

    /// Live like state for a recipe: the current user's like + the like/save counts.
    func likeStateStream(recipeId: String) -> AsyncStream<LikeState> {
        AsyncStream { continuation in
            let uid = self.authRepository.uid
            var state = LikeState()
            let recipeReg = self.db.collection(self.sharedCollection).document(recipeId)
                .addSnapshotListener { snap, _ in
                    state.likeCount = (snap?.get("likeCount") as? Int) ?? 0
                    state.saveCount = (snap?.get("saveCount") as? Int) ?? 0
                    continuation.yield(state)
                }
            let likeReg = uid.map { u in
                self.db.collection(self.sharedCollection).document(recipeId)
                    .collection("likes").document(u)
                    .addSnapshotListener { snap, _ in
                        state.isLiked = snap?.exists == true
                        continuation.yield(state)
                    }
            }
            continuation.onTermination = { _ in recipeReg.remove(); likeReg?.remove() }
        }
    }

    func getSharedRecipeDetail(recipeId: String) async -> SharedRecipe? {
        guard let doc = try? await db.collection(sharedCollection).document(recipeId).getDocument() else { return nil }
        guard let data = doc.data() else { return nil }
        return parseSharedRecipe(id: doc.documentID, data: data)
    }

    // MARK: - Notes (F18 — one cloud thread on every recipe; reuses the Comment model)

    /// Record the recipe owner on the notes parent doc so they can moderate (delete any note).
    func ensureNotesParent(recipeId: String) async {
        guard let uid = authRepository.uid else { return }
        try? await notesDoc(recipeId).setData(["recipeAuthorId": uid], merge: true)
    }

    /// Owner-only: freeze / unfreeze new notes (existing notes are kept).
    func setNotesLocked(recipeId: String, locked: Bool) async -> Bool {
        do {
            try await notesDoc(recipeId).setData(
                ["locked": locked, "recipeAuthorId": authRepository.uid as Any], merge: true)
            return true
        } catch { return false }
    }

    func getNotesLocked(recipeId: String) async -> Bool {
        (try? await notesDoc(recipeId).getDocument().get("locked") as? Bool) ?? false
    }

    func commentsStream(recipeId: String) -> AsyncStream<[SharedComment]> {
        AsyncStream { continuation in
            let listener = self.notesCol(recipeId)
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
            try await notesCol(recipeId).document(id).setData(doc)
            return true
        } catch {
            return false
        }
    }

    func deleteComment(recipeId: String, commentId: String) async -> Bool {
        do {
            try await notesCol(recipeId).document(commentId).delete()
            return true
        } catch {
            return false
        }
    }

    // MARK: - Copy to My Recipes

    func copyToMyRecipes(_ sharedRecipe: SharedRecipe) async -> Bool {
        guard let uid = authRepository.uid else { return false }
        let newId = UUID().uuidString

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
                quantityDisplayImperial: ing.quantityDisplayImperial,
                shoppingNote: ing.shoppingNote,
                quantityValueMax: ing.quantityValueMax,
                quantityValueMaxMetric: ing.quantityValueMaxMetric,
                quantityValueMaxImperial: ing.quantityValueMaxImperial
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

    /// Lowercased distinct words (≥2 chars) from title + tags — the searchable token set.
    private static func searchTokens(title: String, tags: [String]) -> [String] {
        let words = (title + " " + tags.joined(separator: " "))
            .lowercased()
            .split { !$0.isLetter && !$0.isNumber }
            .map(String.init)
            .filter { $0.count >= 2 }
        var seen = Set<String>()
        return words.filter { seen.insert($0).inserted }.prefix(30).map { $0 }
    }

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
            if let v = ing.shoppingNote            { d["shoppingNote"] = v }
            if let v = ing.quantityValueMax         { d["quantityValueMax"] = v }
            if let v = ing.quantityValueMaxMetric   { d["quantityValueMaxMetric"] = v }
            if let v = ing.quantityValueMaxImperial { d["quantityValueMaxImperial"] = v }
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
            // F12: write the real tier ("friends" or "public") so the read rule + profile query work.
            "visibility": recipe.visibility == "private" ? "public" : recipe.visibility,
            // F17: per-recipient ACL — the read rule grants access to UIDs in this array.
            "sharedWith": recipe.sharedWith,
            "sharedAt": Date().timeIntervalSince1970 * 1000,
            "updatedAt": recipe.updatedAt.timeIntervalSince1970 * 1000,
            "createdAt": recipe.createdAt.timeIntervalSince1970 * 1000,
            "authorId": recipe.authorId ?? "",
            // v2: always store the real author name + isImported flag. The "Imported by X"
            // vs "X" label is computed at display time — never overwrite the name here.
            "authorDisplayName": recipe.authorDisplayName ?? "User",
            "tags": recipe.tags,
            // F13 Phase 3a: lowercased word tokens (title + tags) for public search.
            "searchTokens": Self.searchTokens(title: recipe.title, tags: recipe.tags),
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
                quantityDisplayImperial: i["quantityDisplayImperial"] as? String,
                shoppingNote: i["shoppingNote"] as? String,
                quantityValueMax: (i["quantityValueMax"] as? NSNumber)?.doubleValue,
                quantityValueMaxMetric: (i["quantityValueMaxMetric"] as? NSNumber)?.doubleValue,
                quantityValueMaxImperial: (i["quantityValueMaxImperial"] as? NSNumber)?.doubleValue
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
            isImported: data["isImported"] as? Bool ?? false,
            visibility: data["visibility"] as? String ?? "public",
            sections: sections,
            ingredients: ingredients,
            steps: steps
        )
    }
}
