import Foundation

/// Lightweight summary of a recipe shared directly to the current user.
/// Used for the Shared inbox list — full detail loaded on tap via SocialRepository.getReceivedRecipe.
struct ReceivedRecipeSummary: Identifiable {
    let shareId: String
    let title: String
    let fromDisplayName: String
    let sharedAt: Date

    var id: String { shareId }
}
