import SwiftData
import Foundation

@Model
final class StepIngredientRefModel {
    var quantityDisplay: String?
    var step: StepModel?
    var ingredient: IngredientModel?

    init(quantityDisplay: String? = nil) {
        self.quantityDisplay = quantityDisplay
    }
}
