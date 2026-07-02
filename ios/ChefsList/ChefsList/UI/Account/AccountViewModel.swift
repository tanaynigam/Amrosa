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
    var pendingFollowAction: String? = nil

    // F13 Phase 3b — explicit cuisine preferences (override Discover affinity)
    var availableCuisines: [String] = []
    var selectedCuisines: Set<String> = []

    // Ingredients — whether optional ingredients start included on a recipe
    var includeOptionalsByDefault: Bool = true

    private static let curatedCuisines = [
        "Indian", "Italian", "Mexican", "Chinese", "Thai", "Japanese",
        "American", "Mediterranean", "French", "Korean", "Middle Eastern"
    ]

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
        let ts = UserDefaults.standard.double(forKey: "chefslist_last_sync")
        lastSyncDate = ts > 0 ? Date(timeIntervalSince1970: ts) : nil
        loadCuisinePrefs()
        includeOptionalsByDefault = container.userPreferences.includeOptionalsByDefault()
    }

    func setIncludeOptionalsByDefault(_ value: Bool) {
        includeOptionalsByDefault = value
        container.userPreferences.setIncludeOptionalsByDefault(value)
    }

    /// Available chips = curated list ∪ the user's own tags (minus meal words); current selection.
    private func loadCuisinePrefs() {
        selectedCuisines = container.userPreferences.cuisinePreferences()
        let ownTags = ((try? repository.fetchMyRecipes()) ?? []).flatMap { $0.tags }
        availableCuisines = (Self.curatedCuisines + ownTags)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !MealClassifier.allMealKeywords.contains($0.lowercased()) }
            .reduce(into: [String]()) { acc, c in
                if !acc.contains(where: { $0.lowercased() == c.lowercased() }) { acc.append(c) }
            }
            .sorted { $0.lowercased() < $1.lowercased() }
    }

    /// Toggle a cuisine preference and persist it (reflected on the next Discover refresh).
    func toggleCuisine(_ cuisine: String) {
        let key = cuisine.lowercased()
        if selectedCuisines.contains(key) { selectedCuisines.remove(key) }
        else { selectedCuisines.insert(key) }
        container.userPreferences.setCuisinePreferences(selectedCuisines)
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
