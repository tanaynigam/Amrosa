import SwiftUI

struct AccountView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: AccountViewModel?
    @State private var showSignOutConfirm = false

    var body: some View {
        List {
            if let vm = viewModel {
                // Profile section — always signed in (auth is mandatory)
                Section("Profile") {
                    VStack(alignment: .leading, spacing: 4) {
                        if let name = vm.displayName {
                            Text(name).font(.headline)
                        }
                        if let email = vm.email {
                            Text(email).font(.subheadline).foregroundStyle(.secondary)
                        }
                        if let phone = vm.phoneNumber {
                            Text(phone).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                // Sign Out
                Section {
                    Button(role: .destructive) {
                        showSignOutConfirm = true
                    } label: {
                        if vm.isSigningOut {
                            ProgressView()
                        } else {
                            Label("Sign Out", systemImage: "rectangle.portrait.and.arrow.right")
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

                if let error = vm.errorMessage {
                    Section {
                        Text(error).foregroundStyle(.red).font(.caption)
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Account")
        .confirmationDialog("Sign Out?", isPresented: $showSignOutConfirm, titleVisibility: .visible) {
            Button("Sign Out", role: .destructive) {
                Task { await viewModel?.signOut() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("All recipes will be removed from this device. They'll sync back automatically when you sign in again.")
        }
        .onAppear {
            if viewModel == nil {
                viewModel = AccountViewModel(
                    authRepository: container.authRepository,
                    repository: container.recipeRepository,
                    container: container
                )
            }
            viewModel?.loadStats()
        }
    }
}
