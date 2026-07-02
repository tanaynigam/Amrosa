import Foundation

/// Lightweight local user preferences (UserDefaults). Currently holds the explicit cuisine
/// preferences that override Discover's implicit affinity. Local-only, never synced.
@MainActor
final class UserPreferences {
    private let defaults = UserDefaults.standard
    private let key = "chefslist_cuisine_prefs"

    /// Preferred cuisine tags (lowercased). Empty = fall back to implicit affinity.
    func cuisinePreferences() -> Set<String> {
        Set((defaults.array(forKey: key) as? [String]) ?? [])
    }

    func setCuisinePreferences(_ cuisines: Set<String>) {
        defaults.set(Array(cuisines.map { $0.lowercased() }), forKey: key)
    }

    // ── Optional-ingredient default ─────────────────────────────────────────────

    /// Whether optional ingredients start included (their chip pre-selected) on a recipe. Default true.
    func includeOptionalsByDefault() -> Bool {
        defaults.object(forKey: Keys.optDefault) == nil ? true : defaults.bool(forKey: Keys.optDefault)
    }

    func setIncludeOptionalsByDefault(_ value: Bool) {
        defaults.set(value, forKey: Keys.optDefault)
    }

    // ── Per-recipe last selections (remembered across visits) ───────────────────

    /// Last substitute choices for a recipe: substituteGroupId → selected ingredientId.
    func recipeSubstitutes(_ recipeId: String) -> [String: String] {
        (defaults.dictionary(forKey: Keys.subs + recipeId) as? [String: String]) ?? [:]
    }

    func setRecipeSubstitutes(_ recipeId: String, _ map: [String: String]) {
        defaults.set(map, forKey: Keys.subs + recipeId)
    }

    /// Last included optional-ingredient ids for a recipe; nil = no saved choice yet (use default).
    func recipeEnabledOptionals(_ recipeId: String) -> Set<String>? {
        guard defaults.object(forKey: Keys.opts + recipeId) != nil else { return nil }
        return Set((defaults.array(forKey: Keys.opts + recipeId) as? [String]) ?? [])
    }

    func setRecipeEnabledOptionals(_ recipeId: String, _ ids: Set<String>) {
        defaults.set(Array(ids), forKey: Keys.opts + recipeId)
    }

    private enum Keys {
        static let optDefault = "include_optionals_default"
        static let subs = "recipe_subs_"
        static let opts = "recipe_opts_"
    }
}
