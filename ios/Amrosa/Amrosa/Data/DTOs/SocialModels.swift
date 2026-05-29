import Foundation

// MARK: - Social data models (Firestore-only, not stored in SwiftData)

struct UserProfile: Identifiable {
    let id: String      // same as uid for Identifiable conformance
    let uid: String
    let displayName: String
    let photoUrl: String?
}
