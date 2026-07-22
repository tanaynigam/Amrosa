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

/// Full recipe + sender name, used by the received-recipe review screen.
struct ReceivedRecipeData {
    let recipe: SharedRecipe
    let fromDisplayName: String
}

/// A pending share pointer (`shared_to/{uid}/recipes/{shareId}`).
/// The actual recipe is resolved from `shared_recipes/{recipeId}`.
struct ReceivedPointer {
    let shareId: String
    let recipeId: String
    let authorUid: String
    let authorName: String
    let fromDisplayName: String
}
