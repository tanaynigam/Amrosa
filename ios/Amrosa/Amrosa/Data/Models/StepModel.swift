import SwiftData
import Foundation

@Model
final class StepModel {
    @Attribute(.unique) var id: String
    var instruction: String
    var orderIndex: Int
    var recipe: RecipeModel?
    var section: RecipeSectionModel?

    @Relationship(deleteRule: .cascade, inverse: \StepIngredientRefModel.step)
    var ingredientRefs: [StepIngredientRefModel] = []

    init(id: String, instruction: String, orderIndex: Int) {
        self.id = id
        self.instruction = instruction
        self.orderIndex = orderIndex
    }
}
