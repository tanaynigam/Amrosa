import Foundation

/// Where a Discover recommendation came from — drives the source boost + card label + open behaviour.
enum RecipeSource { case own, friend, `public` }

/// A single recommendation candidate on the Discover tab. Lightweight (card-level).
/// `isLocal` = the recipe exists in SwiftData (open the normal editable detail); otherwise it's a
/// remote mirror opened read-only via the review screen.
struct DiscoverRecipe: Identifiable, Hashable {
    let recipeId: String
    let title: String
    let tags: [String]
    let prepTimeMinutes: Int?
    let cookTimeMinutes: Int?
    let source: RecipeSource
    let authorUid: String?
    let authorName: String?
    let isLocal: Bool
    // F13 Phase 2 — popularity (public recipes only; 0 otherwise).
    var saveCount: Int = 0
    var likeCount: Int = 0

    var id: String { recipeId }
}
