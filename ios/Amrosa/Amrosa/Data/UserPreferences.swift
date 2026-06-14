import Foundation

/// Lightweight local user preferences (UserDefaults). Currently holds the explicit cuisine
/// preferences that override Discover's implicit affinity. Local-only, never synced.
@MainActor
final class UserPreferences {
    private let defaults = UserDefaults.standard
    private let key = "amrosa_cuisine_prefs"

    /// Preferred cuisine tags (lowercased). Empty = fall back to implicit affinity.
    func cuisinePreferences() -> Set<String> {
        Set((defaults.array(forKey: key) as? [String]) ?? [])
    }

    func setCuisinePreferences(_ cuisines: Set<String>) {
        defaults.set(Array(cuisines.map { $0.lowercased() }), forKey: key)
    }
}
