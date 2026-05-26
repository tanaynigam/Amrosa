import Observation
import Foundation

@Observable
@MainActor
final class AccountViewModel {
    var isSigningOut = false
    var errorMessage: String? = nil
    var recipeCount: Int = 0
    var lastSyncDate: Date? = nil

    private let authRepository: AuthRepository
    private let repository: RecipeRepository
    private let container: AppContainer

    var isSignedIn: Bool { authRepository.isSignedIn }
    var displayName: String? { authRepository.displayName }
    var email: String? { authRepository.email }
    var phoneNumber: String? { authRepository.phoneNumber }
    var uid: String? { authRepository.uid }

    init(authRepository: AuthRepository, repository: RecipeRepository, container: AppContainer) {
        self.authRepository = authRepository
        self.repository = repository
        self.container = container
    }

    func loadStats() {
        recipeCount = (try? repository.count()) ?? 0
        let ts = UserDefaults.standard.double(forKey: "amrosa_last_sync")
        lastSyncDate = ts > 0 ? Date(timeIntervalSince1970: ts) : nil
    }

    /// Clears all local data then signs out.
    /// Auth state change in ContentView handles the navigation back to auth gate.
    func signOut() async {
        isSigningOut = true
        defer { isSigningOut = false }
        do {
            container.clearAllLocalData()
            try authRepository.signOut()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
