package com.aerion.chefslist.ui.discover

import com.aerion.chefslist.domain.model.DiscoverRecipe
import com.aerion.chefslist.domain.model.RecipeSource
import kotlin.math.ln

/**
 * Pure, client-side ranking for the Discover feed. Score combines a time-of-day meal match,
 * cuisine affinity learned from the user's own collection, a source boost (own > friend > public),
 * and a recency penalty so a just-cooked recipe isn't suggested again immediately.
 */
object DiscoverRanker {

    private const val RECENT_WINDOW_MS = 48L * 60 * 60 * 1000  // 2 days

    /** Cuisine affinity from the user's own recipes: tag frequency minus meal words, top entries. */
    fun topCuisines(ownTags: List<String>, limit: Int = 5): Set<String> =
        ownTags.map { it.lowercase().trim() }
            .filter { it.isNotBlank() && it !in MealClassifier.allMealKeywords }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit).map { it.key }.toSet()

    fun score(
        recipe: DiscoverRecipe,
        currentMeal: MealType,
        topCuisines: Set<String>,
        cookedAt: Map<String, Long>,
        now: Long,
    ): Double {
        val meals = MealClassifier.mealsFor(recipe.tags)
        val mealMatch = when {
            meals.isEmpty() -> 0.3       // unclassified — mildly eligible any time
            currentMeal in meals -> 1.0
            else -> 0.0
        }
        val affinity = recipe.tags.count { it.lowercase().trim() in topCuisines }
            .coerceAtMost(2) * 0.5       // 0.0 – 1.0
        val sourceBoost = when (recipe.source) {
            RecipeSource.OWN -> 0.6
            RecipeSource.FRIEND -> 0.4
            RecipeSource.PUBLIC -> 0.2
        }
        // Popularity (public only): saves weigh more than likes; log-scaled so a few saves
        // help but a viral recipe doesn't dominate every shelf.
        val popularity = if (recipe.source == RecipeSource.PUBLIC) {
            val raw = recipe.saveCount * 2 + recipe.likeCount
            (ln(1.0 + raw) * 0.5).coerceAtMost(1.5)
        } else 0.0
        val last = cookedAt[recipe.recipeId]
        val recencyPenalty = if (last != null && now - last < RECENT_WINDOW_MS) 1.5 else 0.0
        return 2.0 * mealMatch + affinity + sourceBoost + popularity - recencyPenalty
    }

    fun rank(
        candidates: List<DiscoverRecipe>,
        currentMeal: MealType,
        topCuisines: Set<String>,
        cookedAt: Map<String, Long>,
        now: Long,
    ): List<DiscoverRecipe> =
        candidates.sortedByDescending { score(it, currentMeal, topCuisines, cookedAt, now) }
}
