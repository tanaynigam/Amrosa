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

    var isSignedIn: Bool { authRepository.isSignedIn }
    var displayName: String? { authRepository.displayName }
    var email: String? { authRepository.email }
    var phoneNumber: String? { authRepository.phoneNumber }
    var uid: String? { authRepository.uid }

    init(authRepository: AuthRepository, repository: RecipeRepository) {
        self.authRepository = authRepository
        self.repository = repository
    }

    func loadStats() {
        recipeCount = (try? repository.count()) ?? 0
        let ts = UserDefaults.standard.double(forKey: "amrosa_last_sync")
        lastSyncDate = ts > 0 ? Date(timeIntervalSince1970: ts) : nil
    }

    func signOut() async {
        isSigningOut = true
        defer { isSigningOut = false }
        do {
            try authRepository.signOut()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
