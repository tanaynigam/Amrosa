import FirebaseFirestore
import Foundation

@MainActor
final class RecipeSyncService {
    private let db = Firestore.firestore()
    private let repository: RecipeRepository
    private let authRepository: AuthRepository
    private let sharedRecipeService: SharedRecipeService

    private let lastSyncKey = "chefslist_last_sync"
    var lastSyncDate: Date? {
        get {
            let ts = UserDefaults.standard.double(forKey: lastSyncKey)
            return ts > 0 ? Date(timeIntervalSince1970: ts) : nil
        }
        set {
            UserDefaults.standard.set(newValue?.timeIntervalSince1970 ?? 0, forKey: lastSyncKey)
        }
    }

    init(repository: RecipeRepository, authRepository: AuthRepository, sharedRecipeService: SharedRecipeService) {
        self.repository = repository
        self.authRepository = authRepository
        self.sharedRecipeService = sharedRecipeService
    }

    // MARK: - Sync (Recipe Ownership Model v2)

    /// v2: the seeded/"official" `recipes` collection is retired. Sync pulls the user's
    /// own personal recipes (Tab 1) and refreshes received references (Tab 2).
    func sync() async {
        if let uid = authRepository.uid {
            await pullPersonalRecipes(uid: uid)
        }
        await syncReceivedRecipes()
        lastSyncDate = Date()
    }

    // MARK: - Pull personal recipes (Firestore → local)

    func pullPersonalRecipes(uid: String) async {
        let collection = db.collection("personal_recipes").document(uid).collection("recipes")
        guard let snapshot = try? await collection.getDocuments() else { return }
        for doc in snapshot.documents {
            var data = doc.data()
            data["id"] = doc.documentID
            try? repository.upsertFromFirestore(data: data)
        }
    }

    // MARK: - Delete personal recipe (local delete must also remove the cloud copy)

    /// Delete a personal recipe from `personal_recipes/{uid}/recipes/{recipeId}`.
    /// Must be called whenever an owned recipe is deleted locally, otherwise the next
    /// pull would resurrect it.
    func deletePersonalRecipe(_ recipeId: String) async {
        guard let uid = authRepository.uid else { return }
        try? await db.collection("personal_recipes").document(uid)
            .collection("recipes").document(recipeId).delete()
    }

    // MARK: - Received recipes (Tab 2 — references to other users' recipes)

    /// Refresh the Tab 2 received-recipe cache from the canonical public mirrors.
    /// For each reference in `received_recipes/{uid}/items`:
    ///   - read `shared_recipes/{recipeId}` → present: re-cache (propagate edits);
    ///     absent: author went Private/deleted → drop reference + local copy.
    /// Also prunes locally-cached received recipes that no longer have a reference.
    func syncReceivedRecipes() async {
        guard let uid = authRepository.uid else { return }
        guard let refs = try? await db.collection("received_recipes").document(uid)
            .collection("items").getDocuments() else { return }

        var liveIds = Set<String>()
        for refDoc in refs.documents {
            let recipeId = refDoc.data()["recipeId"] as? String ?? refDoc.documentID
            let authorUid = refDoc.data()["authorUid"] as? String
            let referenceName = refDoc.data()["authorName"] as? String
            if let mirror = await sharedRecipeService.getSharedRecipeDetail(recipeId: recipeId) {
                // Prefer the author's live profile name so legacy/iOS recipes that stored
                // the literal "Imported" resolve to the real name ("Imported by Imported" fix).
                let realName = await resolveAuthorName(
                    authorUid: authorUid, referenceName: referenceName, mirrorName: mirror.authorDisplayName
                )
                try? repository.cacheReceivedRecipe(mirror, isImported: mirror.isImported, authorDisplayName: realName)
                liveIds.insert(recipeId)
            } else {
                // Author unpublished (went Private) or deleted → remove ref + local copy
                try? await refDoc.reference.delete()
                try? repository.removeReceivedRecipe(id: recipeId)
            }
        }

        // Prune locally-cached received recipes that no longer have a reference
        if let localIds = try? repository.fetchReceivedRecipeIds() {
            for localId in localIds where !liveIds.contains(localId) {
                try? repository.removeReceivedRecipe(id: localId)
            }
        }
    }

    /// Best real author name for a received recipe. The canonical mirror may store the
    /// literal "Imported" (legacy/iOS) — in that case look up the author's live profile.
    /// Falls back to the saved reference name, then the mirror name.
    private func resolveAuthorName(authorUid: String?, referenceName: String?, mirrorName: String?) async -> String? {
        func ok(_ s: String?) -> Bool {
            guard let s = s, !s.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
            return s.lowercased() != "imported"
        }
        if ok(referenceName) { return referenceName }
        if let authorUid = authorUid,
           let profile = try? await db.collection("users").document(authorUid).getDocument(),
           let name = profile.data()?["displayName"] as? String, ok(name) {
            return name
        }
        return ok(mirrorName) ? mirrorName : (referenceName ?? mirrorName)
    }

    // MARK: - Push one personal recipe (local → Firestore)

    func pushPersonalRecipe(_ recipe: RecipeModel) async {
        guard let uid = authRepository.uid else { return }
        // Received recipes (Tab 2) are references to another user's recipe — never push them as ours.
        if recipe.isReceived { return }

        // Use Int64 milliseconds — not Firestore Timestamp — so Android can read as Long
        var data: [String: Any] = [
            "id": recipe.id,
            "title": recipe.title,
            "baseServings": recipe.baseServings,
            "isCustomized": recipe.isCustomized,
            "isImported": recipe.isImported,
            "needsReview": recipe.needsReview,
            "version": recipe.version,
            "visibility": recipe.visibility,
            "sharedWith": recipe.sharedWith,
            "scaleStep": recipe.scaleStep,
            "updatedAt": Int64(recipe.updatedAt.timeIntervalSince1970 * 1000),
            "createdAt": Int64(recipe.createdAt.timeIntervalSince1970 * 1000)
        ]
        if let v = recipe.recipeDescription { data["description"] = v }
        if let v = recipe.prepTimeMinutes   { data["prepTimeMinutes"] = v }
        if let v = recipe.cookTimeMinutes   { data["cookTimeMinutes"] = v }
        if let v = recipe.imageUrl          { data["imageUrl"] = v }
        if let v = recipe.authorId          { data["authorId"] = v }
        if let v = recipe.authorDisplayName { data["authorDisplayName"] = v }
        if let v = recipe.baseServingsMin   { data["baseServingsMin"] = v }
        if let v = recipe.baseServingsMax   { data["baseServingsMax"] = v }
        if let v = recipe.scaleIngredientId { data["scaleIngredientId"] = v }
        if let v = recipe.parentRecipeId    { data["parentRecipeId"] = v }   // F10: preserve grouping multi-device
        if let v = recipe.variantName       { data["variantName"] = v }

        data["tags"] = recipe.tags
        data["sourceUrls"] = recipe.sourceUrls

        // Push sections
        let sections = recipe.sections.sorted { $0.orderIndex < $1.orderIndex }
        data["sections"] = sections.map { sec -> [String: Any] in
            ["id": sec.id, "name": sec.name, "orderIndex": sec.orderIndex]
        }

        // Push ingredients
        let ingredients = recipe.ingredients.sorted { $0.orderIndex < $1.orderIndex }
        data["ingredients"] = ingredients.map { ing -> [String: Any] in
            var d: [String: Any] = [
                "id": ing.id,
                "name": ing.name,
                "isOptional": ing.isOptional,
                "orderIndex": ing.orderIndex
            ]
            if let v = ing.section?.id        { d["sectionId"] = v }
            if let v = ing.quantityValue       { d["quantityValue"] = v }
            if let v = ing.quantityUnit        { d["quantityUnit"] = v }
            if let v = ing.quantityDisplay     { d["quantityDisplay"] = v }
            if let v = ing.groupLabel          { d["groupLabel"] = v }
            if let v = ing.substituteGroupId   { d["substituteGroupId"] = v }
            d["substituteRatio"] = ing.substituteRatio
            if let v = ing.quantityValueMetric   { d["quantityValueMetric"] = v }
            if let v = ing.quantityUnitMetric    { d["quantityUnitMetric"] = v }
            if let v = ing.quantityDisplayMetric { d["quantityDisplayMetric"] = v }
            if let v = ing.quantityValueImperial   { d["quantityValueImperial"] = v }
            if let v = ing.quantityUnitImperial    { d["quantityUnitImperial"] = v }
            if let v = ing.quantityDisplayImperial { d["quantityDisplayImperial"] = v }
            if let v = ing.shoppingNote          { d["shoppingNote"] = v }
            if let v = ing.quantityValueMax         { d["quantityValueMax"] = v }
            if let v = ing.quantityValueMaxMetric   { d["quantityValueMaxMetric"] = v }
            if let v = ing.quantityValueMaxImperial { d["quantityValueMaxImperial"] = v }
            return d
        }

        // Push steps
        let steps = recipe.steps.sorted { $0.orderIndex < $1.orderIndex }
        data["steps"] = steps.map { step -> [String: Any] in
            var d: [String: Any] = [
                "id": step.id,
                "instruction": step.instruction,
                "orderIndex": step.orderIndex
            ]
            if let v = step.section?.id { d["sectionId"] = v }
            return d
        }

        // Push stepIngredientRefs as a top-level array (same format as Android)
        let refs = recipe.steps.flatMap { step in
            step.ingredientRefs.map { ref -> [String: Any] in
                var d: [String: Any] = [
                    "stepId": step.id,
                    "ingredientId": ref.ingredient?.id ?? ""
                ]
                if let v = ref.quantityDisplay { d["quantityDisplay"] = v }
                return d
            }
        }
        data["stepIngredientRefs"] = refs

        let docRef = db.collection("personal_recipes").document(uid).collection("recipes").document(recipe.id)
        try? await docRef.setData(data)

        recipe.syncedAt = Date()
        try? repository.saveRecipe(recipe)
    }

    // MARK: - Push all personal recipes (e.g. after sign-in)

    func pushAllPersonalRecipes() async {
        guard let allRecipes = try? repository.fetchPersonalRecipes() else { return }
        for recipe in allRecipes {
            await pushPersonalRecipe(recipe)
        }
    }
}
