import SwiftUI

struct AccountView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: AccountViewModel?
    @State private var showSignOutConfirm = false
    @State private var showNotifications = false
    @State private var showUserSearch = false
    @State private var notifShareId: String? = nil
    @State private var showReceivedRecipe = false
    @State private var showEditName = false
    @State private var editNameText = ""

    var body: some View {
        List {
            if let vm = viewModel {
                // Profile section — tappable to edit name
                Section("Profile") {
                    Button {
                        editNameText = vm.displayName ?? ""
                        showEditName = true
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                if let name = vm.displayName {
                                    Text(name).font(.headline).foregroundStyle(.primary)
                                } else {
                                    Text("Add your name").font(.headline).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "pencil")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            if let email = vm.email {
                                Text(email).font(.subheadline).foregroundStyle(.secondary)
                            }
                            if let phone = vm.phoneNumber {
                                Text(phone).font(.subheadline).foregroundStyle(.secondary)
                            }
                            Text("Tap to edit name")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                }

                // People section
                Section("People") {
                    // Pending follow requests
                    if !vm.pendingRequests.isEmpty {
                        ForEach(vm.pendingRequests) { profile in
                            HStack {
                                // Avatar
                                ZStack {
                                    Circle()
                                        .fill(Color.accentColor.opacity(0.12))
                                        .frame(width: 36, height: 36)
                                    Text(String(profile.displayName.prefix(1)).uppercased())
                                        .font(.subheadline)
                                        .foregroundStyle(Color.accentColor)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(profile.displayName).font(.subheadline)
                                    Text("Wants to follow you")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if vm.pendingFollowAction == profile.uid {
                                    ProgressView().scaleEffect(0.8)
                                } else {
                                    HStack(spacing: 8) {
                                        Button("Accept") {
                                            Task { await vm.acceptFollowRequest(profile) }
                                        }
                                        .font(.caption)
                                        .buttonStyle(.borderedProminent)

                                        Button("Decline") {
                                            Task { await vm.declineFollowRequest(profile) }
                                        }
                                        .font(.caption)
                                        .buttonStyle(.bordered)
                                        .tint(.secondary)
                                    }
                                }
                            }
                        }
                    }

                    LabeledContent("Following", value: "\(vm.followingCount) people")

                    Button {
                        showUserSearch = true
                    } label: {
                        Label("Find People", systemImage: "person.badge.plus")
                    }
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
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if let vm = viewModel {
                    Button {
                        showNotifications = true
                    } label: {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "bell")
                            if vm.unreadNotificationCount > 0 {
                                Circle()
                                    .fill(Color.red)
                                    .frame(width: 8, height: 8)
                                    .offset(x: 4, y: -4)
                            }
                        }
                    }
                }
            }
        }
        .confirmationDialog("Sign Out?", isPresented: $showSignOutConfirm, titleVisibility: .visible) {
            Button("Sign Out", role: .destructive) {
                Task { await viewModel?.signOut() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("All recipes will be removed from this device. They'll sync back automatically when you sign in again.")
        }
        .sheet(isPresented: $showNotifications) {
            NavigationStack {
                NotificationsView(
                    onFollowNotification: {
                        showNotifications = false
                        // Already on Account tab — scroll to People section is implicit
                    },
                    onRecipeShared: { shareId in
                        showNotifications = false
                        notifShareId = shareId
                        showReceivedRecipe = true
                    }
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { showNotifications = false }
                    }
                }
            }
        }
        .sheet(isPresented: $showUserSearch) {
            UserSearchView()
        }
        .sheet(isPresented: $showReceivedRecipe) {
            if let shareId = notifShareId {
                NavigationStack {
                    ReceivedRecipeView(shareId: shareId)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { showReceivedRecipe = false }
                        }
                    }
                }
            }
        }
        .alert("Edit Name", isPresented: $showEditName) {
            TextField("Display name", text: $editNameText)
                .autocorrectionDisabled()
            Button("Save") {
                let name = editNameText
                Task { await viewModel?.updateDisplayName(name) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This name is visible to people you follow.")
        }
        .onChange(of: viewModel?.nameUpdateMessage) { _, msg in
            if msg != nil {
                // Auto-clear after 2 seconds
                Task {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    viewModel?.clearNameUpdateMessage()
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let msg = viewModel?.nameUpdateMessage {
                Text(msg)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color(.systemBackground).shadow(.drop(radius: 4)))
                    .clipShape(Capsule())
                    .padding(.bottom, 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .animation(.easeInOut(duration: 0.25), value: msg)
            }
        }
        .onAppear {
            if viewModel == nil {
                viewModel = AccountViewModel(
                    authRepository: container.authRepository,
                    repository: container.recipeRepository,
                    container: container,
                    socialRepository: container.socialRepository
                )
            }
            viewModel?.loadStats()
            viewModel?.startObserving()
        }
        .onDisappear {
            viewModel?.stopObserving()
        }
    }
}
