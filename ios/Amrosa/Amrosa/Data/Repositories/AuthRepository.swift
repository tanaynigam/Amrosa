import FirebaseAuth
import FirebaseCore
import GoogleSignIn
import UIKit

final class AuthRepository {
    private let auth = Auth.auth()

    // MARK: - Current user accessors

    var currentUser: User? { auth.currentUser }

    var isSignedIn: Bool {
        guard let user = auth.currentUser else { return false }
        return !user.isAnonymous
    }

    var uid: String? { auth.currentUser?.uid }
    var displayName: String? { auth.currentUser?.displayName }
    var email: String? { auth.currentUser?.email }
    var phoneNumber: String? { auth.currentUser?.phoneNumber }
    var photoURL: URL? { auth.currentUser?.photoURL }

    // MARK: - Auth state stream

    func authStateStream() -> AsyncStream<User?> {
        AsyncStream { continuation in
            let handle = auth.addStateDidChangeListener { _, user in
                continuation.yield(user)
            }
            continuation.onTermination = { _ in
                Auth.auth().removeStateDidChangeListener(handle)
            }
        }
    }

    // MARK: - Anonymous

    func signInAnonymouslyIfNeeded() async {
        guard auth.currentUser == nil else { return }
        _ = try? await auth.signInAnonymously()
    }

    // MARK: - Google

    func signInWithGoogle(presentingViewController: UIViewController) async -> Result<User, Error> {
        do {
            guard let clientID = FirebaseApp.app()?.options.clientID else {
                throw AuthError.missingClientID
            }
            let config = GIDConfiguration(clientID: clientID)
            GIDSignIn.sharedInstance.configuration = config

            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presentingViewController)
            guard let idToken = result.user.idToken?.tokenString else {
                throw AuthError.missingIDToken
            }
            let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: result.user.accessToken.tokenString)
            return try await linkOrSignIn(with: credential)
        } catch {
            return .failure(error)
        }
    }

    // MARK: - Email / Password

    func signInWithEmail(email: String, password: String) async -> Result<User, Error> {
        do {
            let credential = EmailAuthProvider.credential(withEmail: email, password: password)
            return try await linkOrSignIn(with: credential)
        } catch {
            // Fall back to direct sign-in if not anonymous
            do {
                let result = try await auth.signIn(withEmail: email, password: password)
                return .success(result.user)
            } catch {
                return .failure(error)
            }
        }
    }

    func signUpWithEmail(name: String, email: String, password: String) async -> Result<User, Error> {
        do {
            let credential = EmailAuthProvider.credential(withEmail: email, password: password)
            let linkResult = try await linkOrSignIn(with: credential)
            if case .success(let user) = linkResult {
                let request = user.createProfileChangeRequest()
                request.displayName = name.trimmingCharacters(in: .whitespaces)
                try await request.commitChanges()
                return .success(user)
            }
            return linkResult
        } catch {
            do {
                let result = try await auth.createUser(withEmail: email, password: password)
                let request = result.user.createProfileChangeRequest()
                request.displayName = name.trimmingCharacters(in: .whitespaces)
                try await request.commitChanges()
                return .success(result.user)
            } catch {
                return .failure(error)
            }
        }
    }

    // MARK: - Phone

    func verifyPhoneNumber(_ phoneNumber: String) async -> Result<String, Error> {
        do {
            let verificationID = try await PhoneAuthProvider.provider().verifyPhoneNumber(phoneNumber, uiDelegate: nil)
            return .success(verificationID)
        } catch {
            return .failure(error)
        }
    }

    func signInWithPhoneCredential(verificationID: String, verificationCode: String) async -> Result<User, Error> {
        do {
            let credential = PhoneAuthProvider.provider().credential(withVerificationID: verificationID, verificationCode: verificationCode)
            return try await linkOrSignIn(with: credential)
        } catch {
            return .failure(error)
        }
    }

    // MARK: - Sign Out

    func signOut() throws {
        try auth.signOut()
    }

    // MARK: - Private helpers

    private func linkOrSignIn(with credential: AuthCredential) async throws -> Result<User, Error> {
        if auth.currentUser?.isAnonymous == true {
            let result = try await auth.currentUser!.link(with: credential)
            return .success(result.user)
        } else {
            let result = try await auth.signIn(with: credential)
            return .success(result.user)
        }
    }
}

enum AuthError: LocalizedError {
    case missingClientID
    case missingIDToken

    var errorDescription: String? {
        switch self {
        case .missingClientID: return "Firebase client ID is missing. Check GoogleService-Info.plist."
        case .missingIDToken: return "Google sign-in did not return an ID token."
        }
    }
}
