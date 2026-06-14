import Foundation

enum MealType: String, CaseIterable {
    case breakfast = "Breakfast"
    case lunch = "Lunch"
    case dinner = "Dinner"
    case snack = "Snack"
    case dessert = "Dessert"
    var label: String { rawValue }
}

/// Heuristic meal-type classification from recipe tags (no schema change — works on existing data).
/// A recipe can match multiple meals; an unclassifiable recipe is eligible for any slot.
enum MealClassifier {
    private static let keywords: [MealType: [String]] = [
        .breakfast: ["breakfast", "brunch", "pancake", "waffle", "omelet", "omelette",
                     "egg", "oats", "oatmeal", "cereal", "toast", "smoothie", "granola"],
        .dessert: ["dessert", "cookie", "cake", "brownie", "sweet", "chocolate",
                   "ice cream", "pudding", "pie", "pastry", "cupcake", "candy", "cheesecake"],
        .snack: ["snack", "appetizer", "starter", "dip", "finger food", "nibble"],
        // Savory mains serve both lunch and dinner.
        .dinner: ["dinner", "lunch", "main", "curry", "pizza", "pasta", "rice",
                  "biryani", "soup", "stew", "sandwich", "burger", "salad", "roast", "grill",
                  "noodle", "stir fry", "taco", "bowl"]
    ]

    /// All meal keywords — used to exclude meal words when inferring cuisine affinity.
    static let allMealKeywords: Set<String> = Set(keywords.values.flatMap { $0 })

    static func mealsFor(_ tags: [String]) -> Set<MealType> {
        let hay = tags.joined(separator: " ").lowercased()
        var result = Set<MealType>()
        for (meal, kws) in keywords where kws.contains(where: { hay.contains($0) }) {
            result.insert(meal)
        }
        if result.contains(.dinner) { result.insert(.lunch) } // savory mains → both
        return result
    }

    /// Map an hour-of-day (0–23) to the meal slot to lead the feed with.
    static func currentMeal(hour: Int) -> MealType {
        switch hour {
        case 5...10: return .breakfast
        case 11...14: return .lunch
        case 15...16: return .snack
        case 17...21: return .dinner
        default: return .dessert
        }
    }
}
