import Foundation

// MARK: - Social data models (Firestore-only, not stored in SwiftData)

struct UserProfile: Identifiable {
    let id: String      // same as uid for Identifiable conformance
    let uid: String
    let displayName: String
    let photoUrl: String?
}

struct SocialNotification: Identifiable {
    let id: String
    let type: String        // "follow_request" | "follow_accepted" | "recipe_shared"
    let fromUid: String
    let fromDisplayName: String
    let shareId: String?    // for recipe_shared
    let recipeName: String? // for recipe_shared
    let createdAt: Date
    let read: Bool
}
