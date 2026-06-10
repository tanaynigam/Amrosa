import Foundation

struct ParsedRecipeData {
    let title: String
    let description: String?
    let sourceUrls: [String]
    let baseServings: Int
    let baseServingsMin: Int?
    let baseServingsMax: Int?
    let prepTimeMinutes: Int?
    let cookTimeMinutes: Int?
    let imageUrl: String?
    let tags: [String]
    let sections: [ParsedSection]
    let ingredients: [ParsedIngredient]
    let steps: [ParsedStep]
    let stepIngredientRefs: [ParsedStepRef]
    let parseNotes: String?
}

struct ParsedSection: Codable {
    let id: String
    let name: String
    let orderIndex: Int
}

struct ParsedIngredient: Codable {
    let id: String
    let sectionId: String?
    let name: String
    let quantityValue: Double?
    let quantityUnit: String?
    let quantityDisplay: String?
    let groupLabel: String?
    let isOptional: Bool
    let orderIndex: Int
    let quantityValueMetric: Double?
    let quantityUnitMetric: String?
    let quantityDisplayMetric: String?
    let quantityValueImperial: Double?
    let quantityUnitImperial: String?
    let quantityDisplayImperial: String?
    /// F11 shopping note — set by Firestore pull paths, never by Gemini import/freeform.
    var shoppingNote: String? = nil
}

struct ParsedStep: Codable {
    let id: String
    let sectionId: String?
    let instruction: String
    let orderIndex: Int
}

struct ParsedStepRef: Codable {
    let stepId: String
    let ingredientId: String
    let quantityDisplay: String?
}
