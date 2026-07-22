import Foundation

// MARK: - Display value types (F16)
//
// Lightweight value types the detail body renders. Both the real `RecipeModel` (view mode) and
// the live edit draft (edit mode) map into these, so a SINGLE set of row views renders both —
// the iOS analog of Android's domain `Recipe`/`Ingredient`/`Step` (decoupled from the store).
// In edit mode quantities are pre-scaled at 1× / original units.

struct DisplaySection: Identifiable {
    let id: String
    let name: String
}

struct DisplaySubstituteChip: Identifiable {
    let id: String               // ingredient id (group member)
    let name: String
    let isSelected: Bool
}

struct DisplayIngredient: Identifiable {
    let id: String
    let sectionId: String?
    let name: String
    let scaledQuantity: String   // already scaled + unit-converted by the caller
    let isOptional: Bool
    let isOptionalEnabled: Bool
    let shoppingNote: String?
    // Substitutes: the group id + sibling options shown as inline swap chips under the row (view mode).
    let substituteGroupId: String?
    let substituteOptions: [DisplaySubstituteChip]
}

struct DisplayIngredientGroup: Identifiable {
    let id: String
    let label: String            // "" = ungrouped (no sub-header)
    let items: [DisplayIngredient]
}

struct DisplayOptionalChip: Identifiable {
    let id: String               // ingredient id
    let name: String
    let isEnabled: Bool          // currently included in the recipe
}

struct DisplayIngredientBlock: Identifiable {
    let id: String               // section id, or "__other__"
    let sectionId: String?       // ghost "＋ Add ingredient" target (nil = no section)
    let title: String?           // nil when no section sub-header is needed
    let optionalChips: [DisplayOptionalChip]  // per-section opt-in chips (view mode only)
    let groups: [DisplayIngredientGroup]
}

struct DisplayStepRef: Identifiable {
    let id: String
    let text: String
}

struct DisplayStep: Identifiable {
    let id: String
    let number: Int
    let instruction: String
    let refs: [DisplayStepRef]
}
