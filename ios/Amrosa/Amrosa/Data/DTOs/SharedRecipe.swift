import Foundation

// MARK: - Shared recipe models (Firestore-only, not stored in SwiftData)

struct SharedRecipe: Identifiable {
    let id: String
    let title: String
    let description: String?
    let sourceUrls: [String]
    let baseServings: Int
    let baseServingsMin: Int?
    let baseServingsMax: Int?
    let scaleIngredientId: String?
    let scaleStep: Double
    let prepTimeMinutes: Int?
    let cookTimeMinutes: Int?
    let imageUrl: String?
    let tags: [String]
    let authorId: String?
    let authorDisplayName: String?
    /// URL/file-imported vs typed — drives the "Imported by X" label (v2).
    let isImported: Bool
    let visibility: String
    let sections: [SharedSection]
    let ingredients: [SharedIngredient]
    let steps: [SharedStep]
}

struct SharedSection: Identifiable {
    let id: String
    let name: String
    let orderIndex: Int
}

struct SharedIngredient: Identifiable {
    let id: String
    let sectionId: String?
    let name: String
    let quantityValue: Double?
    let quantityUnit: String?
    let quantityDisplay: String?
    let groupLabel: String?
    let isOptional: Bool
    let orderIndex: Int
    // F6 conversions
    let quantityValueMetric: Double?
    let quantityUnitMetric: String?
    let quantityDisplayMetric: String?
    let quantityValueImperial: Double?
    let quantityUnitImperial: String?
    let quantityDisplayImperial: String?
    /// F11 shopping note — travels with the recipe through sharing.
    var shoppingNote: String? = nil

    var hasConversionData: Bool { quantityValueMetric != nil || quantityValueImperial != nil }
}

struct SharedStep: Identifiable {
    let id: String
    let sectionId: String?
    let instruction: String
    let orderIndex: Int
    let ingredientRefs: [SharedStepRef]
}

struct SharedStepRef {
    let ingredientId: String
    let quantityDisplay: String?
}

struct SharedComment: Identifiable {
    let id: String
    let recipeId: String
    let authorId: String
    let authorDisplayName: String
    let content: String
    let createdAt: Date
}
