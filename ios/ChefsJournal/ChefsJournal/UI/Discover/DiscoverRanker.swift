import Foundation

/// Pure, client-side ranking for the Discover feed. Score combines a time-of-day meal match,
/// cuisine affinity learned from the user's own collection, a source boost (own > friend > public),
/// a popularity term (public only), and a recency penalty so a just-cooked recipe isn't re-suggested.
enum DiscoverRanker {
    private static let recentWindow: TimeInterval = 48 * 60 * 60  // 2 days

    /// Cuisine affinity from the user's own recipes: tag frequency minus meal words, top entries.
    static func topCuisines(_ ownTags: [String], limit: Int = 5) -> Set<String> {
        var counts: [String: Int] = [:]
        for tag in ownTags {
            let t = tag.lowercased().trimmingCharacters(in: .whitespaces)
            guard !t.isEmpty, !MealClassifier.allMealKeywords.contains(t) else { continue }
            counts[t, default: 0] += 1
        }
        return Set(counts.sorted { $0.value > $1.value }.prefix(limit).map { $0.key })
    }

    static func score(
        _ recipe: DiscoverRecipe,
        currentMeal: MealType,
        topCuisines: Set<String>,
        cookedAt: [String: Date],
        now: Date
    ) -> Double {
        let meals = MealClassifier.mealsFor(recipe.tags)
        let mealMatch: Double = meals.isEmpty ? 0.3 : (meals.contains(currentMeal) ? 1.0 : 0.0)
        let affinity = Double(min(recipe.tags.filter { topCuisines.contains($0.lowercased().trimmingCharacters(in: .whitespaces)) }.count, 2)) * 0.5
        let sourceBoost: Double = {
            switch recipe.source { case .own: return 0.6; case .friend: return 0.4; case .public: return 0.2 }
        }()
        // Popularity (public only): saves weigh more than likes; log-scaled and capped.
        let popularity: Double = recipe.source == .public
            ? min(log(1.0 + Double(recipe.saveCount * 2 + recipe.likeCount)) * 0.5, 1.5)
            : 0.0
        let recencyPenalty: Double = {
            guard let last = cookedAt[recipe.recipeId] else { return 0.0 }
            return now.timeIntervalSince(last) < recentWindow ? 1.5 : 0.0
        }()
        return 2.0 * mealMatch + affinity + sourceBoost + popularity - recencyPenalty
    }

    static func rank(
        _ candidates: [DiscoverRecipe],
        currentMeal: MealType,
        topCuisines: Set<String>,
        cookedAt: [String: Date],
        now: Date
    ) -> [DiscoverRecipe] {
        candidates.sorted {
            score($0, currentMeal: currentMeal, topCuisines: topCuisines, cookedAt: cookedAt, now: now) >
            score($1, currentMeal: currentMeal, topCuisines: topCuisines, cookedAt: cookedAt, now: now)
        }
    }
}
