import FirebaseFirestore
import Foundation

@MainActor
final class RecipeSyncService {
    private let db = Firestore.firestore()
    private let repository: RecipeRepository
    private let authRepository: AuthRepository

    private let lastSyncKey = "amrosa_last_sync"
    var lastSyncDate: Date? {
        get {
            let ts = UserDefaults.standard.double(forKey: lastSyncKey)
            return ts > 0 ? Date(timeIntervalSince1970: ts) : nil
        }
        set {
            UserDefaults.standard.set(newValue?.timeIntervalSince1970 ?? 0, forKey: lastSyncKey)
        }
    }

    init(repository: RecipeRepository, authRepository: AuthRepository) {
        self.repository = repository
        self.authRepository = authRepository
    }

    // MARK: - Sync (pull both seeded + personal)

    func sync() async {
        await pullSeededRecipes()
        if let uid = authRepository.uid {
            await pullPersonalRecipes(uid: uid)
        }
        lastSyncDate = Date()
    }

    // MARK: - Pull seeded/shared recipes (Firestore → local)

    func pullSeededRecipes() async {
        var query = db.collection("recipes").limit(to: 100)
        if let last = lastSyncDate {
            query = query.whereField("updatedAt", isGreaterThan: Timestamp(date: last))
        }
        guard let snapshot = try? await query.getDocuments() else { return }
        for doc in snapshot.documents {
            var data = doc.data()
            data["id"] = doc.documentID
            try? repository.upsertFromFirestore(data: data)
        }
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

    // MARK: - Push one personal recipe (local → Firestore)

    func pushPersonalRecipe(_ recipe: RecipeModel) async {
        guard let uid = authRepository.uid else { return }

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
