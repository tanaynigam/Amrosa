import SwiftData
import Foundation

@Model
final class RecipeNoteModel {
    @Attribute(.unique) var id: String
    var content: String
    var createdAt: Date
    var updatedAt: Date
    var recipe: RecipeModel?

    init(id: String, content: String, createdAt: Date = Date(), updatedAt: Date = Date()) {
        self.id = id
        self.content = content
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}
