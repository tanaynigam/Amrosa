import Foundation

/// Card-level data for a recipe shared directly to the current user.
struct ReceivedRecipeSummary: Identifiable {
    let shareId: String
    let title: String
    /// Original recipe author (e.g. "Tanay" or "Imported").
    let authorDisplayName: String
    /// Who sent this recipe to you.
    let fromDisplayName: String
    let sharedAt: Date
    let prepTimeMinutes: Int?
    let cookTimeMinutes: Int?
    let tags: [String]

    var id: String { shareId }
}

/// Full recipe + sender name, returned by getReceivedRecipe().
struct ReceivedRecipeData {
    let recipe: SharedRecipe
    let fromDisplayName: String
}
