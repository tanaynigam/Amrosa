import Observation
import Foundation
import UIKit
import FirebaseAuth

@Observable
@MainActor
final class AuthViewModel {
    enum AuthMode { case signIn, createAccount }
    enum PhoneStep { case enterNumber, enterCode }

    var mode: AuthMode = .signIn
    var email = ""
    var password = ""
    var name = ""
    var phoneNumber = ""
    var otpCode = ""
    var phoneStep: PhoneStep = .enterNumber
    var verificationID: String? = nil

    var isLoading = false
    var errorMessage: String? = nil
    var isAuthenticated = false

    private let authRepository: AuthRepository

    init(authRepository: AuthRepository) {
        self.authRepository = authRepository
    }

    // MARK: - Google

    func signInWithGoogle(from viewController: UIViewController) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        let result = await authRepository.signInWithGoogle(presentingViewController: viewController)
        switch result {
        case .success: isAuthenticated = true
        case .failure(let error): errorMessage = error.localizedDescription
        }
    }

    // MARK: - Email

    func submitEmailForm() async {
        guard !email.isEmpty, !password.isEmpty else {
            errorMessage = "Please enter your email and password."
            return
        }
        if mode == .createAccount && name.isEmpty {
            errorMessage = "Please enter your name."
            return
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        let result: Result<FirebaseAuth.User, Error>
        if mode == .signIn {
            result = await authRepository.signInWithEmail(email: email, password: password)
        } else {
            result = await authRepository.signUpWithEmail(name: name, email: email, password: password)
        }

        switch result {
        case .success: isAuthenticated = true
        case .failure(let error): errorMessage = error.localizedDescription
        }
    }

    // MARK: - Phone

    func sendOTP() async {
        guard !phoneNumber.isEmpty else {
            errorMessage = "Please enter a phone number."
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        let result = await authRepository.verifyPhoneNumber(phoneNumber)
        switch result {
        case .success(let id):
            verificationID = id
            phoneStep = .enterCode
        case .failure(let error):
            errorMessage = error.localizedDescription
        }
    }

    func verifyOTP() async {
        guard let vid = verificationID, !otpCode.isEmpty else {
            errorMessage = "Please enter the code."
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        let result = await authRepository.signInWithPhoneCredential(verificationID: vid, verificationCode: otpCode)
        switch result {
        case .success: isAuthenticated = true
        case .failure(let error): errorMessage = error.localizedDescription
        }
    }
}
