import SwiftData
import Foundation

/// A checked-off item on a recipe's Shopping List (F11). Presence of a row = checked.
/// `itemKey` is the normalized ingredient name (the combined shopping-list line key).
/// Personal + local only — never synced to Firestore.
@Model
final class ShoppingCheckModel {
    /// Composite key "recipeId|itemKey" (SwiftData has no composite primary keys).
    @Attribute(.unique) var key: String
    var recipeId: String
    var itemKey: String

    init(recipeId: String, itemKey: String) {
        self.key = "\(recipeId)|\(itemKey)"
        self.recipeId = recipeId
        self.itemKey = itemKey
    }
}
