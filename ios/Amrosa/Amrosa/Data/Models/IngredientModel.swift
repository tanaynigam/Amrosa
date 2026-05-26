import SwiftData
import Foundation

@Model
final class IngredientModel {
    @Attribute(.unique) var id: String
    var name: String
    var quantityValue: Double?
    var quantityUnit: String?
    var quantityDisplay: String?
    var groupLabel: String?
    var isOptional: Bool
    var substituteGroupId: String?
    var substituteRatio: Float
    var orderIndex: Int
    // F6 — unit conversion fields
    var quantityValueMetric: Double?
    var quantityUnitMetric: String?
    var quantityDisplayMetric: String?
    var quantityValueImperial: Double?
    var quantityUnitImperial: String?
    var quantityDisplayImperial: String?

    var recipe: RecipeModel?
    var section: RecipeSectionModel?

    @Relationship(deleteRule: .cascade, inverse: \StepIngredientRefModel.ingredient)
    var stepRefs: [StepIngredientRefModel] = []

    init(
        id: String,
        name: String,
        quantityValue: Double? = nil,
        quantityUnit: String? = nil,
        quantityDisplay: String? = nil,
        groupLabel: String? = nil,
        isOptional: Bool = false,
        substituteGroupId: String? = nil,
        substituteRatio: Float = 1.0,
        orderIndex: Int,
        quantityValueMetric: Double? = nil,
        quantityUnitMetric: String? = nil,
        quantityDisplayMetric: String? = nil,
        quantityValueImperial: Double? = nil,
        quantityUnitImperial: String? = nil,
        quantityDisplayImperial: String? = nil
    ) {
        self.id = id
        self.name = name
        self.quantityValue = quantityValue
        self.quantityUnit = quantityUnit
        self.quantityDisplay = quantityDisplay
        self.groupLabel = groupLabel
        self.isOptional = isOptional
        self.substituteGroupId = substituteGroupId
        self.substituteRatio = substituteRatio
        self.orderIndex = orderIndex
        self.quantityValueMetric = quantityValueMetric
        self.quantityUnitMetric = quantityUnitMetric
        self.quantityDisplayMetric = quantityDisplayMetric
        self.quantityValueImperial = quantityValueImperial
        self.quantityUnitImperial = quantityUnitImperial
        self.quantityDisplayImperial = quantityDisplayImperial
    }

    var hasConversionData: Bool {
        quantityValueMetric != nil || quantityValueImperial != nil
    }
}
