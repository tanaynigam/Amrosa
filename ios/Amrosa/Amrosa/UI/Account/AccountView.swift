import SwiftUI

struct AccountView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: AccountViewModel?
    @State private var navigateToAuth = false
    @State private var showSignOutConfirm = false

    var body: some View {
        List {
            if let vm = viewModel {
                // Profile section
                Section {
                    if vm.isSignedIn {
                        VStack(alignment: .leading, spacing: 4) {
                            if let name = vm.displayName {
                                Text(name)
                                    .font(.headline)
                            }
                            if let email = vm.email {
                                Text(email)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            if let phone = vm.phoneNumber {
                                Text(phone)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                    } else {
                        Text("Not signed in")
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Profile")
                }

                // Auth action
                Section {
                    if vm.isSignedIn {
                        Button(role: .destructive) {
                            showSignOutConfirm = true
                        } label: {
                            if vm.isSigningOut {
                                ProgressView()
                            } else {
                                Text("Sign Out")
                            }
                        }
                    } else {
                        Button {
                            navigateToAuth = true
                        } label: {
                            Label("Sign In / Create Account", systemImage: "person.badge.plus")
                        }
                    }
                }

                // Sync & Storage
                Section("Sync & Storage") {
                    LabeledContent("Recipes saved", value: "\(vm.recipeCount)")
                    if let syncDate = vm.lastSyncDate {
                        LabeledContent("Last synced", value: syncDate.relativeString())
                    } else {
                        LabeledContent("Last synced", value: "Never")
                    }
                }

                // About
                Section("About") {
                    LabeledContent("App", value: "Amrosa")
                    LabeledContent("Version", value: "1.0")
                    LabeledContent("By", value: "Aerion")
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Account")
        .navigationDestination(isPresented: $navigateToAuth) {
            AuthView()
        }
        .confirmationDialog("Sign Out", isPresented: $showSignOutConfirm) {
            Button("Sign Out", role: .destructive) {
                Task { await viewModel?.signOut() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to sign out?")
        }
        .onAppear {
            if viewModel == nil {
                viewModel = AccountViewModel(
                    authRepository: container.authRepository,
                    repository: container.recipeRepository
                )
            }
            viewModel?.loadStats()
        }
    }
}
