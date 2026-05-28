import FirebaseFirestore
import Foundation

/// Manages follow relationships, notifications, and direct recipe sharing.
///
/// Firestore collections:
///   users/{uid}                              — public user profiles
///   follows/{followerId}_{followeeId}        — status: "pending" | "accepted"
///   notifications/{uid}/items/{notifId}      — per-user notification inbox
///   shared_to/{recipientUid}/recipes/{shareId} — directly shared recipe data
@MainActor
final class SocialRepository {
    private let db = Firestore.firestore()
    private let authRepository: AuthRepository

    init(authRepository: AuthRepository) {
        self.authRepository = authRepository
    }

    // MARK: - Profile

    /// Create or merge the current user's public profile document.
    func upsertProfile() async {
        guard let uid = authRepository.uid else { return }
        let displayName = authRepository.displayName ?? authRepository.email ?? uid
        var data: [String: Any] = [
            "uid": uid,
            "displayName": displayName
        ]
        if let email = authRepository.email { data["email"] = email }
        if let photoURL = authRepository.photoURL?.absoluteString { data["photoUrl"] = photoURL }
        try? await db.collection("users").document(uid).setData(data, merge: true)
    }

    // MARK: - Search

    /// Prefix search for users by display name (case-sensitive prefix).
    /// Excludes the current user. Returns up to 20 results.
    func searchUsers(query: String) async -> [UserProfile] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        let currentUid = authRepository.uid ?? ""
        let prefix = query.trimmingCharacters(in: .whitespacesAndNewlines)
        // Firestore prefix range query: field >= prefix AND field < prefix + "\u{f8ff}"
        let end = prefix + "\u{f8ff}"
        do {
            let snapshot = try await db.collection("users")
                .whereField("displayName", isGreaterThanOrEqualTo: prefix)
                .whereField("displayName", isLessThan: end)
                .limit(to: 20)
                .getDocuments()
            return snapshot.documents.compactMap { doc -> UserProfile? in
                let data = doc.data()
                guard let uid = data["uid"] as? String,
                      uid != currentUid,
                      let displayName = data["displayName"] as? String else { return nil }
                return UserProfile(
                    id: uid,
                    uid: uid,
                    displayName: displayName,
                    photoUrl: data["photoUrl"] as? String
                )
            }
        } catch {
            return []
        }
    }

    // MARK: - Follow actions

    /// Send a follow request to targetUid. Creates a "pending" follow doc and delivers a notification.
    func sendFollowRequest(targetUid: String, targetName: String) async {
        guard let myUid = authRepository.uid,
              let myName = authRepository.displayName ?? authRepository.email else { return }
        let followId = "\(myUid)_\(targetUid)"
        let data: [String: Any] = [
            "followerId": myUid,
            "followeeId": targetUid,
            "status": "pending",
            "createdAt": Int64(Date().timeIntervalSince1970 * 1000)
        ]
        try? await db.collection("follows").document(followId).setData(data)
        await deliverNotification(
            toUid: targetUid,
            type: "follow_request",
            fromDisplayName: myName
        )
    }

    /// Accept a follow request from fromUid.
    /// Updates the follow status to "accepted", notifies the requester, and marks the follow_request notification as read.
    func acceptFollowRequest(fromUid: String) async {
        guard let myUid = authRepository.uid,
              let myName = authRepository.displayName ?? authRepository.email else { return }
        let followId = "\(fromUid)_\(myUid)"
        try? await db.collection("follows").document(followId).updateData(["status": "accepted"])
        await deliverNotification(
            toUid: fromUid,
            type: "follow_accepted",
            fromDisplayName: myName
        )
        // Mark the corresponding follow_request notification as read
        await markFollowRequestNotificationsRead(fromUid: fromUid)
    }

    /// Decline (reject) a follow request from fromUid — deletes the follow doc and marks notification read.
    func declineFollowRequest(fromUid: String) async {
        guard let myUid = authRepository.uid else { return }
        let followId = "\(fromUid)_\(myUid)"
        try? await db.collection("follows").document(followId).delete()
        await markFollowRequestNotificationsRead(fromUid: fromUid)
    }

    /// Unfollow a user (delete the accepted follow doc).
    func unfollow(targetUid: String) async {
        guard let myUid = authRepository.uid else { return }
        let followId = "\(myUid)_\(targetUid)"
        try? await db.collection("follows").document(followId).delete()
    }

    /// One-shot check of follow status toward targetUid.
    /// Returns "none" | "pending" | "accepted".
    func getFollowStatus(targetUid: String) async -> String {
        guard let myUid = authRepository.uid else { return "none" }
        let followId = "\(myUid)_\(targetUid)"
        guard let doc = try? await db.collection("follows").document(followId).getDocument(),
              doc.exists,
              let status = doc.data()?["status"] as? String else {
            return "none"
        }
        return status
    }

    // MARK: - Live streams

    /// Live stream of pending follow requests directed at the current user.
    func pendingRequestsStream() -> AsyncStream<[UserProfile]> {
        guard let myUid = authRepository.uid else { return AsyncStream { $0.finish() } }
        return AsyncStream { continuation in
            let listener = self.db.collection("follows")
                .whereField("followeeId", isEqualTo: myUid)
                .whereField("status", isEqualTo: "pending")
                .addSnapshotListener { snapshot, _ in
                    guard let docs = snapshot?.documents else {
                        continuation.yield([])
                        return
                    }
                    let followerIds = docs.compactMap { $0.data()["followerId"] as? String }
                    if followerIds.isEmpty {
                        continuation.yield([])
                        return
                    }
                    // Fetch profiles for each pending requester
                    Task { @MainActor in
                        var profiles: [UserProfile] = []
                        for uid in followerIds {
                            if let doc = try? await self.db.collection("users").document(uid).getDocument(),
                               let data = doc.data(),
                               let displayName = data["displayName"] as? String {
                                profiles.append(UserProfile(
                                    id: uid,
                                    uid: uid,
                                    displayName: displayName,
                                    photoUrl: data["photoUrl"] as? String
                                ))
                            }
                        }
                        continuation.yield(profiles)
                    }
                }
            continuation.onTermination = { _ in listener.remove() }
        }
    }

    /// Live stream of users the current user is following (accepted).
    func followingStream() -> AsyncStream<[UserProfile]> {
        guard let myUid = authRepository.uid else { return AsyncStream { $0.finish() } }
        return AsyncStream { continuation in
            let listener = self.db.collection("follows")
                .whereField("followerId", isEqualTo: myUid)
                .whereField("status", isEqualTo: "accepted")
                .addSnapshotListener { snapshot, _ in
                    guard let docs = snapshot?.documents else {
                        continuation.yield([])
                        return
                    }
                    let followeeIds = docs.compactMap { $0.data()["followeeId"] as? String }
                    if followeeIds.isEmpty {
                        continuation.yield([])
                        return
                    }
                    Task { @MainActor in
                        var profiles: [UserProfile] = []
                        for uid in followeeIds {
                            if let doc = try? await self.db.collection("users").document(uid).getDocument(),
                               let data = doc.data(),
                               let displayName = data["displayName"] as? String {
                                profiles.append(UserProfile(
                                    id: uid,
                                    uid: uid,
                                    displayName: displayName,
                                    photoUrl: data["photoUrl"] as? String
                                ))
                            }
                        }
                        continuation.yield(profiles)
                    }
                }
            continuation.onTermination = { _ in listener.remove() }
        }
    }

    // MARK: - Notifications

    /// Live stream of the last 50 notifications for the current user, newest first.
    func notificationsStream() -> AsyncStream<[SocialNotification]> {
        guard let myUid = authRepository.uid else { return AsyncStream { $0.finish() } }
        return AsyncStream { continuation in
            let listener = self.db.collection("notifications")
                .document(myUid)
                .collection("items")
                .order(by: "createdAt", descending: true)
                .limit(to: 50)
                .addSnapshotListener { snapshot, _ in
                    guard let docs = snapshot?.documents else {
                        continuation.yield([])
                        return
                    }
                    let notifications = docs.compactMap { doc -> SocialNotification? in
                        let data = doc.data()
                        guard let type = data["type"] as? String,
                              let fromUid = data["fromUid"] as? String,
                              let fromDisplayName = data["fromDisplayName"] as? String else { return nil }
                        let createdAtMs = (data["createdAt"] as? Double) ?? 0
                        return SocialNotification(
                            id: doc.documentID,
                            type: type,
                            fromUid: fromUid,
                            fromDisplayName: fromDisplayName,
                            shareId: data["shareId"] as? String,
                            recipeName: data["recipeName"] as? String,
                            createdAt: Date(timeIntervalSince1970: createdAtMs / 1000),
                            read: data["read"] as? Bool ?? false
                        )
                    }
                    continuation.yield(notifications)
                }
            continuation.onTermination = { _ in listener.remove() }
        }
    }

    /// Live stream of unread notification count for the current user.
    func unreadCountStream() -> AsyncStream<Int> {
        guard let myUid = authRepository.uid else { return AsyncStream { $0.yield(0) } }
        return AsyncStream { continuation in
            let listener = self.db.collection("notifications")
                .document(myUid)
                .collection("items")
                .whereField("read", isEqualTo: false)
                .addSnapshotListener { snapshot, _ in
                    continuation.yield(snapshot?.documents.count ?? 0)
                }
            continuation.onTermination = { _ in listener.remove() }
        }
    }

    /// Mark a single notification as read.
    func markNotificationRead(notifId: String) async {
        guard let myUid = authRepository.uid else { return }
        try? await db.collection("notifications")
            .document(myUid)
            .collection("items")
            .document(notifId)
            .updateData(["read": true])
    }

    /// Mark all notifications as read for the current user.
    func markAllNotificationsRead() async {
        guard let myUid = authRepository.uid else { return }
        guard let snapshot = try? await db.collection("notifications")
            .document(myUid)
            .collection("items")
            .whereField("read", isEqualTo: false)
            .getDocuments() else { return }
        for doc in snapshot.documents {
            try? await doc.reference.updateData(["read": true])
        }
    }

    // MARK: - Direct recipe sharing

    /// Share a recipe directly to a follower. Writes to `shared_to/{recipientUid}/recipes/{shareId}`
    /// and delivers a "recipe_shared" notification.
    /// Returns true on success.
    func shareRecipeTo(recipientUid: String, recipientName: String, recipe: RecipeModel) async -> Bool {
        guard let myUid = authRepository.uid,
              let myName = authRepository.displayName ?? authRepository.email else { return false }
        let shareId = UUID().uuidString
        var doc = buildSharedToDocument(recipe: recipe, fromUid: myUid, fromDisplayName: myName)
        doc["shareId"] = shareId

        do {
            try await db.collection("shared_to")
                .document(recipientUid)
                .collection("recipes")
                .document(shareId)
                .setData(doc)
            await deliverNotification(
                toUid: recipientUid,
                type: "recipe_shared",
                fromDisplayName: myName,
                shareId: shareId,
                recipeName: recipe.title
            )
            return true
        } catch {
            return false
        }
    }

    /// Load a directly-shared recipe from `shared_to/{currentUid}/recipes/{shareId}`.
    func getReceivedRecipe(shareId: String) async -> SharedRecipe? {
        guard let myUid = authRepository.uid else { return nil }
        guard let doc = try? await db.collection("shared_to")
            .document(myUid)
            .collection("recipes")
            .document(shareId)
            .getDocument(),
              let data = doc.data() else { return nil }
        return parseReceivedRecipe(id: shareId, data: data)
    }

    // MARK: - Private helpers

    private func deliverNotification(
        toUid: String,
        type: String,
        fromDisplayName: String,
        shareId: String? = nil,
        recipeName: String? = nil
    ) async {
        guard let myUid = authRepository.uid else { return }
        let notifId = UUID().uuidString
        var data: [String: Any] = [
            "type": type,
            "fromUid": myUid,
            "fromDisplayName": fromDisplayName,
            "read": false,
            "createdAt": Int64(Date().timeIntervalSince1970 * 1000)
        ]
        if let shareId = shareId { data["shareId"] = shareId }
        if let recipeName = recipeName { data["recipeName"] = recipeName }
        try? await db.collection("notifications")
            .document(toUid)
            .collection("items")
            .document(notifId)
            .setData(data)
    }

    /// Mark all follow_request notifications from a specific sender as read.
    private func markFollowRequestNotificationsRead(fromUid: String) async {
        guard let myUid = authRepository.uid else { return }
        guard let snapshot = try? await db.collection("notifications")
            .document(myUid)
            .collection("items")
            .whereField("type", isEqualTo: "follow_request")
            .whereField("fromUid", isEqualTo: fromUid)
            .whereField("read", isEqualTo: false)
            .getDocuments() else { return }
        for doc in snapshot.documents {
            try? await doc.reference.updateData(["read": true])
        }
    }

    /// Build the Firestore document for `shared_to/{recipientUid}/recipes/{shareId}`.
    /// Same structure as SharedRecipeService.buildDocument but uses fromUid/fromDisplayName instead of authorId/authorDisplayName.
    private func buildSharedToDocument(recipe: RecipeModel, fromUid: String, fromDisplayName: String) -> [String: Any] {
        let sections = recipe.sections.sorted { $0.orderIndex < $1.orderIndex }.map { s -> [String: Any] in
            ["id": s.id, "name": s.name, "orderIndex": s.orderIndex]
        }
        let ingredients = recipe.ingredients.sorted { $0.orderIndex < $1.orderIndex }.map { ing -> [String: Any] in
            var d: [String: Any] = [
                "id": ing.id,
                "name": ing.name,
                "isOptional": ing.isOptional,
                "orderIndex": ing.orderIndex
            ]
            if let v = ing.section?.id          { d["sectionId"] = v }
            if let v = ing.quantityValue         { d["quantityValue"] = v }
            if let v = ing.quantityUnit          { d["quantityUnit"] = v }
            if let v = ing.quantityDisplay       { d["quantityDisplay"] = v }
            if let v = ing.groupLabel            { d["groupLabel"] = v }
            if let v = ing.substituteGroupId     { d["substituteGroupId"] = v }
            if let v = ing.quantityValueMetric   { d["quantityValueMetric"] = v }
            if let v = ing.quantityUnitMetric    { d["quantityUnitMetric"] = v }
            if let v = ing.quantityDisplayMetric { d["quantityDisplayMetric"] = v }
            if let v = ing.quantityValueImperial  { d["quantityValueImperial"] = v }
            if let v = ing.quantityUnitImperial   { d["quantityUnitImperial"] = v }
            if let v = ing.quantityDisplayImperial { d["quantityDisplayImperial"] = v }
            return d
        }
        let steps = recipe.steps.sorted { $0.orderIndex < $1.orderIndex }.map { step -> [String: Any] in
            var d: [String: Any] = [
                "id": step.id,
                "instruction": step.instruction,
                "orderIndex": step.orderIndex
            ]
            if let v = step.section?.id { d["sectionId"] = v }
            return d
        }
        let refs = recipe.steps.flatMap { step in
            step.ingredientRefs.map { ref -> [String: Any] in
                var d: [String: Any] = ["stepId": step.id, "ingredientId": ref.ingredient?.id ?? ""]
                if let v = ref.quantityDisplay { d["quantityDisplay"] = v }
                return d
            }
        }

        var doc: [String: Any] = [
            "title": recipe.title,
            "baseServings": recipe.baseServings,
            "isCustomized": recipe.isCustomized,
            "isImported": recipe.isImported,
            "version": recipe.version,
            "visibility": "private",
            "sharedAt": Int64(Date().timeIntervalSince1970 * 1000),
            "updatedAt": Int64(recipe.updatedAt.timeIntervalSince1970 * 1000),
            "createdAt": Int64(recipe.createdAt.timeIntervalSince1970 * 1000),
            "fromUid": fromUid,
            "fromDisplayName": fromDisplayName,
            "tags": recipe.tags,
            "sourceUrls": recipe.sourceUrls,
            "sections": sections,
            "ingredients": ingredients,
            "steps": steps,
            "stepIngredientRefs": refs
        ]
        if let v = recipe.recipeDescription    { doc["description"] = v }
        if let v = recipe.prepTimeMinutes       { doc["prepTimeMinutes"] = v }
        if let v = recipe.cookTimeMinutes       { doc["cookTimeMinutes"] = v }
        if let v = recipe.imageUrl              { doc["imageUrl"] = v }
        if let v = recipe.baseServingsMin       { doc["baseServingsMin"] = v }
        if let v = recipe.baseServingsMax       { doc["baseServingsMax"] = v }
        if let v = recipe.scaleIngredientId     { doc["scaleIngredientId"] = v }
        return doc
    }

    // MARK: - Shared inbox summary

    /// Live stream of lightweight summaries for `shared_to/{uid}/recipes/`, newest first.
    func receivedRecipesSummaryStream() -> AsyncStream<[ReceivedRecipeSummary]> {
        guard let myUid = authRepository.uid else { return AsyncStream { $0.finish() } }
        return AsyncStream { continuation in
            let listener = self.db.collection("shared_to")
                .document(myUid)
                .collection("recipes")
                .order(by: "sharedAt", descending: true)
                .addSnapshotListener { snapshot, _ in
                    guard let docs = snapshot?.documents else {
                        continuation.yield([])
                        return
                    }
                    let summaries = docs.compactMap { doc -> ReceivedRecipeSummary? in
                        let data = doc.data()
                        guard let title = data["title"] as? String else { return nil }
                        let sharedAtMs = (data["sharedAt"] as? Double) ?? 0
                        return ReceivedRecipeSummary(
                            shareId: doc.documentID,
                            title: title,
                            fromDisplayName: data["fromDisplayName"] as? String ?? "Someone",
                            sharedAt: Date(timeIntervalSince1970: sharedAtMs / 1000)
                        )
                    }
                    continuation.yield(summaries)
                }
            continuation.onTermination = { _ in listener.remove() }
        }
    }

    /// Parse a received recipe document — same as SharedRecipeService.parseSharedRecipe
    /// but uses fromUid/fromDisplayName as the author fields.
    private func parseReceivedRecipe(id: String, data: [String: Any]) -> SharedRecipe? {
        guard let title = data["title"] as? String else { return nil }

        let sectionsRaw = data["sections"] as? [[String: Any]] ?? []
        let sections = sectionsRaw.compactMap { s -> SharedSection? in
            guard let sid = s["id"] as? String, let name = s["name"] as? String else { return nil }
            return SharedSection(id: sid, name: name, orderIndex: s["orderIndex"] as? Int ?? 0)
        }.sorted { $0.orderIndex < $1.orderIndex }

        let ingredientsRaw = data["ingredients"] as? [[String: Any]] ?? []
        let ingredients = ingredientsRaw.compactMap { i -> SharedIngredient? in
            guard let iid = i["id"] as? String, let name = i["name"] as? String else { return nil }
            return SharedIngredient(
                id: iid,
                sectionId: i["sectionId"] as? String,
                name: name,
                quantityValue: (i["quantityValue"] as? NSNumber)?.doubleValue,
                quantityUnit: i["quantityUnit"] as? String,
                quantityDisplay: i["quantityDisplay"] as? String,
                groupLabel: i["groupLabel"] as? String,
                isOptional: i["isOptional"] as? Bool ?? false,
                orderIndex: i["orderIndex"] as? Int ?? 0,
                quantityValueMetric: (i["quantityValueMetric"] as? NSNumber)?.doubleValue,
                quantityUnitMetric: i["quantityUnitMetric"] as? String,
                quantityDisplayMetric: i["quantityDisplayMetric"] as? String,
                quantityValueImperial: (i["quantityValueImperial"] as? NSNumber)?.doubleValue,
                quantityUnitImperial: i["quantityUnitImperial"] as? String,
                quantityDisplayImperial: i["quantityDisplayImperial"] as? String
            )
        }.sorted { $0.orderIndex < $1.orderIndex }

        let refsRaw = data["stepIngredientRefs"] as? [[String: Any]] ?? []
        let refsByStep: [String: [SharedStepRef]] = Dictionary(
            grouping: refsRaw.compactMap { r -> (String, SharedStepRef)? in
                guard let stepId = r["stepId"] as? String, let ingId = r["ingredientId"] as? String else { return nil }
                return (stepId, SharedStepRef(ingredientId: ingId, quantityDisplay: r["quantityDisplay"] as? String))
            },
            by: { $0.0 }
        ).mapValues { $0.map(\.1) }

        let stepsRaw = data["steps"] as? [[String: Any]] ?? []
        let steps = stepsRaw.compactMap { s -> SharedStep? in
            guard let sid = s["id"] as? String, let instruction = s["instruction"] as? String else { return nil }
            return SharedStep(
                id: sid,
                sectionId: s["sectionId"] as? String,
                instruction: instruction,
                orderIndex: s["orderIndex"] as? Int ?? 0,
                ingredientRefs: refsByStep[sid] ?? []
            )
        }.sorted { $0.orderIndex < $1.orderIndex }

        let sourceUrls: [String]
        if let urls = data["sourceUrls"] as? [String] { sourceUrls = urls }
        else { sourceUrls = [] }

        let tags: [String]
        if let t = data["tags"] as? [String] { tags = t }
        else { tags = [] }

        // fromUid / fromDisplayName used as author fields for received recipes
        return SharedRecipe(
            id: id,
            title: title,
            description: data["description"] as? String,
            sourceUrls: sourceUrls,
            baseServings: data["baseServings"] as? Int ?? 1,
            baseServingsMin: data["baseServingsMin"] as? Int,
            baseServingsMax: data["baseServingsMax"] as? Int,
            scaleIngredientId: data["scaleIngredientId"] as? String,
            scaleStep: (data["scaleStep"] as? NSNumber)?.doubleValue ?? 1.0,
            prepTimeMinutes: data["prepTimeMinutes"] as? Int,
            cookTimeMinutes: data["cookTimeMinutes"] as? Int,
            imageUrl: data["imageUrl"] as? String,
            tags: tags,
            authorId: data["fromUid"] as? String,
            authorDisplayName: data["fromDisplayName"] as? String,
            visibility: "private",
            sections: sections,
            ingredients: ingredients,
            steps: steps
        )
    }
}
