import SwiftData
import Foundation

@Model
final class RecipeSectionModel {
    @Attribute(.unique) var id: String
    var name: String
    var orderIndex: Int
    var recipe: RecipeModel?

    @Relationship(deleteRule: .cascade, inverse: \IngredientModel.section)
    var ingredients: [IngredientModel] = []

    @Relationship(deleteRule: .cascade, inverse: \StepModel.section)
    var steps: [StepModel] = []

    init(id: String, name: String, orderIndex: Int) {
        self.id = id
        self.name = name
        self.orderIndex = orderIndex
    }
}
