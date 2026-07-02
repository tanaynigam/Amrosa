import Foundation

struct RecipeChange: Codable {
    let version: Int
    let timestamp: TimeInterval
    let summary: String
}
