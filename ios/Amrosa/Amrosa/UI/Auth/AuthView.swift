import SwiftUI

struct AuthView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: AuthViewModel?
    @State private var showPhoneFlow = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Heading
                VStack(spacing: 4) {
                    Text("Amrita & Ambrosia")
                        .font(.largeTitle)
                        .fontWeight(.bold)
                    Text("Sign in to sync and share your recipes.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 24)

                if let vm = viewModel {
                    // Mode picker
                    Picker("Mode", selection: Binding(
                        get: { vm.mode },
                        set: { vm.mode = $0 }
                    )) {
                        Text("Sign In").tag(AuthViewModel.AuthMode.signIn)
                        Text("Create Account").tag(AuthViewModel.AuthMode.createAccount)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)

                    // Google Sign In
                    GoogleSignInButton {
                        Task {
                            guard let vc = UIApplication.shared.connectedScenes
                                .compactMap({ $0 as? UIWindowScene })
                                .first?.windows.first?.rootViewController else { return }
                            await vm.signInWithGoogle(from: vc)
                        }
                    }
                    .padding(.horizontal)

                    // Divider
                    HStack {
                        Rectangle().fill(Color(.separator)).frame(height: 1)
                        Text("or").font(.caption).foregroundStyle(.secondary)
                        Rectangle().fill(Color(.separator)).frame(height: 1)
                    }
                    .padding(.horizontal)

                    // Email form
                    VStack(spacing: 12) {
                        if vm.mode == .createAccount {
                            TextField("Name", text: Binding(get: { vm.name }, set: { vm.name = $0 }))
                                .textContentType(.name)
                                .textFieldStyle(.roundedBorder)
                        }

                        TextField("Email", text: Binding(get: { vm.email }, set: { vm.email = $0 }))
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .autocapitalization(.none)
                            .textFieldStyle(.roundedBorder)

                        SecureField("Password", text: Binding(get: { vm.password }, set: { vm.password = $0 }))
                            .textContentType(vm.mode == .createAccount ? .newPassword : .password)
                            .textFieldStyle(.roundedBorder)

                        Button {
                            Task { await vm.submitEmailForm() }
                        } label: {
                            Group {
                                if vm.isLoading {
                                    ProgressView()
                                } else {
                                    Text(vm.mode == .signIn ? "Sign In" : "Create Account")
                                        .fontWeight(.semibold)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(vm.isLoading)
                    }
                    .padding(.horizontal)

                    // Phone option
                    Button("Use phone number") {
                        showPhoneFlow = true
                    }
                    .font(.subheadline)

                    // Error
                    if let error = vm.errorMessage {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .navigationTitle("Sign In")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPhoneFlow) {
            if let vm = viewModel {
                PhoneAuthView(viewModel: vm)
            }
        }
        .onChange(of: viewModel?.isAuthenticated) { _, isAuth in
            if isAuth == true { dismiss() }
        }
        .onAppear {
            if viewModel == nil {
                viewModel = AuthViewModel(authRepository: container.authRepository)
            }
        }
    }
}

// MARK: - Google Sign In Button

struct GoogleSignInButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: "globe")
                    .font(.title3)
                Text("Continue with Google")
                    .fontWeight(.medium)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        }
        .buttonStyle(.bordered)
        .tint(.primary)
    }
}

// MARK: - Phone Auth View

struct PhoneAuthView: View {
    @Bindable var viewModel: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if viewModel.phoneStep == .enterNumber {
                    Text("Enter your phone number")
                        .font(.headline)

                    TextField("+1 555 555 5555", text: Binding(
                        get: { viewModel.phoneNumber },
                        set: { viewModel.phoneNumber = $0 }
                    ))
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)

                    Button {
                        Task { await viewModel.sendOTP() }
                    } label: {
                        Group {
                            if viewModel.isLoading {
                                ProgressView()
                            } else {
                                Text("Send Code")
                                    .fontWeight(.semibold)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.isLoading)
                    .padding(.horizontal)
                } else {
                    Text("Enter the 6-digit code")
                        .font(.headline)

                    TextField("123456", text: Binding(
                        get: { viewModel.otpCode },
                        set: { viewModel.otpCode = $0 }
                    ))
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)

                    Button {
                        Task { await viewModel.verifyOTP() }
                    } label: {
                        Group {
                            if viewModel.isLoading {
                                ProgressView()
                            } else {
                                Text("Verify")
                                    .fontWeight(.semibold)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.isLoading)
                    .padding(.horizontal)
                }

                if let error = viewModel.errorMessage {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
            }
            .padding(.top, 24)
            .navigationTitle("Phone Sign In")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .onChange(of: viewModel.isAuthenticated) { _, isAuth in
            if isAuth { dismiss() }
        }
    }
}
