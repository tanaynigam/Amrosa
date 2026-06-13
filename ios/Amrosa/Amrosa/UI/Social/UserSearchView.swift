import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class UserSearchViewModel {
    var searchQuery: String = ""
    var results: [UserProfile] = []
    var isSearching: Bool = false
    var followStatus: [String: String] = [:]   // uid → "none" | "pending" | "accepted"
    var pendingAction: String? = nil           // uid with in-flight action

    private let socialRepository: SocialRepository
    private var debounceTask: Task<Void, Never>? = nil

    init(socialRepository: SocialRepository) {
        self.socialRepository = socialRepository
    }

    func onQueryChange(_ q: String) {
        searchQuery = q
        debounceTask?.cancel()
        guard !q.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            results = []
            followStatus = [:]
            return
        }
        debounceTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000) // 0.3s debounce
            guard !Task.isCancelled else { return }
            isSearching = true
            let found = await socialRepository.searchUsers(query: q)
            guard !Task.isCancelled else { return }
            results = found
            // Load follow status for each result
            for user in found {
                let status = await socialRepository.getFollowStatus(targetUid: user.uid)
                followStatus[user.uid] = status
            }
            isSearching = false
        }
    }

    func sendFollowRequest(_ user: UserProfile) {
        guard pendingAction == nil else { return }
        pendingAction = user.uid
        Task {
            await socialRepository.sendFollowRequest(targetUid: user.uid, targetName: user.displayName)
            followStatus[user.uid] = "pending"
            pendingAction = nil
        }
    }

    func unfollow(_ user: UserProfile) {
        guard pendingAction == nil else { return }
        pendingAction = user.uid
        Task {
            await socialRepository.unfriend(targetUid: user.uid)
            followStatus[user.uid] = "none"
            pendingAction = nil
        }
    }
}

// MARK: - View

struct UserSearchView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: UserSearchViewModel?
    @State private var selectedProfile: UserProfile?

    var body: some View {
        NavigationStack {
            Group {
                if let vm = viewModel {
                    UserSearchContent(viewModel: vm, onOpenProfile: { selectedProfile = $0 })
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Find People")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(item: $selectedProfile) { p in
                ProfileView(uid: p.uid, name: p.displayName)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear {
            if viewModel == nil {
                viewModel = UserSearchViewModel(socialRepository: container.socialRepository)
            }
        }
    }
}

// MARK: - Content

private struct UserSearchContent: View {
    @Bindable var viewModel: UserSearchViewModel
    var onOpenProfile: (UserProfile) -> Void = { _ in }

    var body: some View {
        VStack(spacing: 0) {
            // Search field
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("Search by name…", text: Binding(
                    get: { viewModel.searchQuery },
                    set: { viewModel.onQueryChange($0) }
                ))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                if viewModel.isSearching {
                    ProgressView().scaleEffect(0.8)
                }
            }
            .padding(10)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .padding()

            if viewModel.results.isEmpty && !viewModel.searchQuery.isEmpty && !viewModel.isSearching {
                Spacer()
                Text("No users found")
                    .foregroundStyle(.secondary)
                Spacer()
            } else {
                List(viewModel.results) { user in
                    UserSearchRow(
                        user: user,
                        status: viewModel.followStatus[user.uid] ?? "none",
                        isPending: viewModel.pendingAction == user.uid,
                        onFollow: { viewModel.sendFollowRequest(user) },
                        onUnfollow: { viewModel.unfollow(user) },
                        onOpenProfile: { onOpenProfile(user) }
                    )
                }
                .listStyle(.plain)
            }
        }
    }
}

// MARK: - Row

private struct UserSearchRow: View {
    let user: UserProfile
    let status: String       // "none" | "pending" | "accepted"
    let isPending: Bool      // in-flight network action
    let onFollow: () -> Void
    let onUnfollow: () -> Void
    var onOpenProfile: () -> Void = {}

    var body: some View {
        HStack(spacing: 12) {
            // Avatar + name → open the chef's profile
            Button(action: onOpenProfile) {
                HStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.accentColor.opacity(0.15))
                            .frame(width: 42, height: 42)
                        Text(String(user.displayName.prefix(1)).uppercased())
                            .font(.headline)
                            .foregroundStyle(Color.accentColor)
                    }
                    Text(user.displayName)
                        .font(.body)
                        .foregroundStyle(.primary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Spacer()

            // Follow button
            if isPending {
                ProgressView()
                    .scaleEffect(0.8)
                    .frame(width: 90)
            } else {
                switch status {
                case "accepted":
                    Button("Co-Chef ✓") { onUnfollow() }
                        .font(.subheadline)
                        .buttonStyle(.bordered)
                        .tint(.secondary)
                case "pending":
                    Button("Requested") {}
                        .font(.subheadline)
                        .buttonStyle(.bordered)
                        .tint(.secondary)
                        .disabled(true)
                default: // "none"
                    Button("Add Co-Chef") { onFollow() }
                        .font(.subheadline)
                        .buttonStyle(.borderedProminent)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
