import Foundation

/// A recipe shown on a chef's profile (F12). Read from the `shared_recipes` mirror.
struct ProfileRecipeSummary: Identifiable, Hashable {
    let recipeId: String
    let title: String
    let prepTimeMinutes: Int?
    let cookTimeMinutes: Int?
    let tags: [String]
    /// "friends" or "public" — drives the small tier badge on the card.
    let visibility: String

    var id: String { recipeId }
}
