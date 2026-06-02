import SwiftData
import Foundation

@Model
final class RecipeModel {
    @Attribute(.unique) var id: String
    var title: String
    var recipeDescription: String?
    var sourceUrlsJson: String
    var baseServings: Int
    var baseServingsMin: Int?
    var baseServingsMax: Int?
    var scaleIngredientId: String?
    var scaleStep: Double
    var prepTimeMinutes: Int?
    var cookTimeMinutes: Int?
    var imageUrl: String?
    var tagsJson: String
    var isCustomized: Bool
    var isImported: Bool
    /// true = saved from another user's share → Tab 2, read-only reference (Recipe Ownership Model v2)
    var isReceived: Bool = false
    var needsReview: Bool
    var version: Int
    var changeLogJson: String
    var createdAt: Date
    var updatedAt: Date
    var syncedAt: Date?
    var authorId: String?
    var authorDisplayName: String?
    /// "private" or "public". Controls visibility in shared_recipes.
    var visibility: String

    @Relationship(deleteRule: .cascade, inverse: \RecipeSectionModel.recipe)
    var sections: [RecipeSectionModel] = []

    @Relationship(deleteRule: .cascade, inverse: \IngredientModel.recipe)
    var ingredients: [IngredientModel] = []

    @Relationship(deleteRule: .cascade, inverse: \StepModel.recipe)
    var steps: [StepModel] = []

    @Relationship(deleteRule: .cascade, inverse: \RecipeNoteModel.recipe)
    var notes: [RecipeNoteModel] = []

    init(
        id: String,
        title: String,
        recipeDescription: String? = nil,
        sourceUrls: [String] = [],
        baseServings: Int,
        baseServingsMin: Int? = nil,
        baseServingsMax: Int? = nil,
        scaleIngredientId: String? = nil,
        scaleStep: Double = 1.0,
        prepTimeMinutes: Int? = nil,
        cookTimeMinutes: Int? = nil,
        imageUrl: String? = nil,
        tags: [String] = [],
        isCustomized: Bool = false,
        isImported: Bool = false,
        isReceived: Bool = false,
        needsReview: Bool = false,
        version: Int = 1,
        changeLog: [RecipeChange] = [],
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        syncedAt: Date? = nil,
        authorId: String? = nil,
        authorDisplayName: String? = nil,
        visibility: String = "private"
    ) {
        self.id = id
        self.title = title
        self.recipeDescription = recipeDescription
        self.sourceUrlsJson = Self.encode(sourceUrls)
        self.baseServings = baseServings
        self.baseServingsMin = baseServingsMin
        self.baseServingsMax = baseServingsMax
        self.scaleIngredientId = scaleIngredientId
        self.scaleStep = scaleStep
        self.prepTimeMinutes = prepTimeMinutes
        self.cookTimeMinutes = cookTimeMinutes
        self.imageUrl = imageUrl
        self.tagsJson = Self.encode(tags)
        self.isCustomized = isCustomized
        self.isImported = isImported
        self.isReceived = isReceived
        self.needsReview = needsReview
        self.version = version
        self.changeLogJson = Self.encode(changeLog)
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.syncedAt = syncedAt
        self.authorId = authorId
        self.authorDisplayName = authorDisplayName
        self.visibility = visibility
    }

    private static func encode<T: Encodable>(_ value: T) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let str = String(data: data, encoding: .utf8) else { return "[]" }
        return str
    }

    var sourceUrls: [String] {
        decode(sourceUrlsJson) ?? []
    }

    var tags: [String] {
        decode(tagsJson) ?? []
    }

    var changeLog: [RecipeChange] {
        decode(changeLogJson) ?? []
    }

    private func decode<T: Decodable>(_ json: String) -> T? {
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    var displayYield: String {
        if let min = baseServingsMin, let max = baseServingsMax {
            return "\(min)–\(max)"
        }
        return "\(baseServings)"
    }

    var totalTimeMinutes: Int? {
        guard let prep = prepTimeMinutes, let cook = cookTimeMinutes else {
            return prepTimeMinutes ?? cookTimeMinutes
        }
        return prep + cook
    }
}
