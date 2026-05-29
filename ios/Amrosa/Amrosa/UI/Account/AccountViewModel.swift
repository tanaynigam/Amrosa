import Observation
import Foundation

@Observable
@MainActor
final class AccountViewModel {
    var isSigningOut = false
    var errorMessage: String? = nil
    var recipeCount: Int = 0
    var lastSyncDate: Date? = nil
    var nameUpdateMessage: String? = nil

    // Social
    var pendingRequests: [UserProfile] = []
    var followingCount: Int = 0
    var unreadNotificationCount: Int = 0
    var pendingFollowAction: String? = nil

    private let authRepository: AuthRepository
    private let repository: RecipeRepository
    private let container: AppContainer
    private let socialRepository: SocialRepository
    private var socialTasks: [Task<Void, Never>] = []

    var isSignedIn: Bool { authRepository.isSignedIn }
    var displayName: String? { authRepository.displayName }
    var email: String? { authRepository.email }
    var phoneNumber: String? { authRepository.phoneNumber }
    var uid: String? { authRepository.uid }

    init(authRepository: AuthRepository, repository: RecipeRepository, container: AppContainer, socialRepository: SocialRepository) {
        self.authRepository = authRepository
        self.repository = repository
        self.container = container
        self.socialRepository = socialRepository
    }

    func loadStats() {
        recipeCount = (try? repository.count()) ?? 0
        let ts = UserDefaults.standard.double(forKey: "amrosa_last_sync")
        lastSyncDate = ts > 0 ? Date(timeIntervalSince1970: ts) : nil
    }

    /// Start live social observers. Called from AccountView.onAppear.
    func startObserving() {
        guard socialTasks.isEmpty else { return }

        socialTasks.append(Task {
            for await profiles in socialRepository.pendingRequestsStream() {
                guard !Task.isCancelled else { break }
                pendingRequests = profiles
            }
        })

        socialTasks.append(Task {
            for await profiles in socialRepository.friendsStream() {
                guard !Task.isCancelled else { break }
                followingCount = profiles.count
            }
        })

        socialTasks.append(Task {
            for await count in socialRepository.unreadCountStream() {
                guard !Task.isCancelled else { break }
                unreadNotificationCount = count
            }
        })
    }

    func stopObserving() {
        socialTasks.forEach { $0.cancel() }
        socialTasks = []
    }

    func acceptFollowRequest(_ profile: UserProfile) async {
        pendingFollowAction = profile.uid
        await socialRepository.acceptFollowRequest(fromUid: profile.uid)
        pendingFollowAction = nil
    }

    func declineFollowRequest(_ profile: UserProfile) async {
        pendingFollowAction = profile.uid
        await socialRepository.declineFollowRequest(fromUid: profile.uid)
        pendingFollowAction = nil
    }

    /// Update the signed-in user's display name in Firebase and re-upsert the public profile.
    func updateDisplayName(_ name: String) async {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        do {
            try await authRepository.updateDisplayName(name)
            await socialRepository.upsertProfile()
            nameUpdateMessage = "Name updated"
        } catch {
            nameUpdateMessage = "Failed to update name: \(error.localizedDescription)"
        }
    }

    func clearNameUpdateMessage() {
        nameUpdateMessage = nil
    }

    /// Clears all local data then signs out.
    /// Auth state change in ContentView handles the navigation back to auth gate.
    func signOut() async {
        isSigningOut = true
        defer { isSigningOut = false }
        do {
            stopObserving()
            container.clearAllLocalData()
            try authRepository.signOut()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
