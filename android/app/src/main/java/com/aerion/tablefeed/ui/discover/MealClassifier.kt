package com.aerion.tablefeed.ui.discover

enum class MealType(val label: String) {
    BREAKFAST("Breakfast"), LUNCH("Lunch"), DINNER("Dinner"), SNACK("Snack"), DESSERT("Dessert")
}

/**
 * Heuristic meal-type classification from recipe tags (no schema change — works on existing data).
 * A recipe can match multiple meals; an unclassifiable recipe is eligible for any slot.
 */
object MealClassifier {

    private val keywords: Map<MealType, List<String>> = mapOf(
        MealType.BREAKFAST to listOf("breakfast", "brunch", "pancake", "waffle", "omelet", "omelette",
            "egg", "oats", "oatmeal", "cereal", "toast", "smoothie", "granola"),
        MealType.DESSERT to listOf("dessert", "cookie", "cake", "brownie", "sweet", "chocolate",
            "ice cream", "pudding", "pie", "pastry", "cupcake", "candy", "cheesecake"),
        MealType.SNACK to listOf("snack", "appetizer", "starter", "dip", "finger food", "nibble"),
        // Savory mains serve both lunch and dinner.
        MealType.DINNER to listOf("dinner", "lunch", "main", "curry", "pizza", "pasta", "rice",
            "biryani", "soup", "stew", "sandwich", "burger", "salad", "roast", "grill",
            "noodle", "stir fry", "taco", "bowl"),
    )

    /** All meal keywords flattened — used to exclude meal words when inferring cuisine affinity. */
    val allMealKeywords: Set<String> = keywords.values.flatten().toSet()

    fun mealsFor(tags: List<String>): Set<MealType> {
        val hay = tags.joinToString(" ").lowercase()
        val result = mutableSetOf<MealType>()
        keywords.forEach { (meal, kws) -> if (kws.any { hay.contains(it) }) result.add(meal) }
        if (MealType.DINNER in result) result.add(MealType.LUNCH) // savory mains → both
        return result
    }

    /** Map an hour-of-day (0–23) to the meal slot to lead the feed with. */
    fun currentMeal(hour: Int): MealType = when (hour) {
        in 5..10 -> MealType.BREAKFAST
        in 11..14 -> MealType.LUNCH
        in 15..16 -> MealType.SNACK
        in 17..21 -> MealType.DINNER
        else -> MealType.DESSERT
    }
}
