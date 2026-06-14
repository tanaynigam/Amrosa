import SwiftData
import Foundation

/// Last-cooked timestamp per recipe (F13). Written by Cooking Mode "Done"; drives the
/// Discover recency penalty + "Recently cooked" shelf. Local only — never synced.
@Model
final class CookedLogModel {
    @Attribute(.unique) var recipeId: String
    var cookedAt: Date

    init(recipeId: String, cookedAt: Date = Date()) {
        self.recipeId = recipeId
        self.cookedAt = cookedAt
    }
}
