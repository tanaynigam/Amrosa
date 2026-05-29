import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class FriendsViewModel {
    var friends: [UserProfile] = []
    var isLoading = true
    var pendingUnfriend: String? = nil       // uid of in-flight unfriend
    var confirmUnfriendTarget: UserProfile? = nil
    private var streamTask: Task<Void, Never>? = nil
    private let socialRepository: SocialRepository

    init(socialRepository: SocialRepository) {
        self.socialRepository = socialRepository
    }

    func start() {
        guard streamTask == nil else { return }
        streamTask = Task {
            for await batch in socialRepository.friendsStream() {
                guard !Task.isCancelled else { break }
                friends = batch
                isLoading = false
            }
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
    }

    func unfriend(_ profile: UserProfile) {
        pendingUnfriend = profile.uid
        Task {
            await socialRepository.unfriend(targetUid: profile.uid)
            pendingUnfriend = nil
        }
    }
}

// MARK: - View

struct FriendsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: FriendsViewModel?

    var body: some View {
        Group {
            if let vm = viewModel {
                FriendsContent(viewModel: vm)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Co-Chefs")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if viewModel == nil {
                viewModel = FriendsViewModel(socialRepository: container.socialRepository)
            }
            viewModel?.start()
        }
        .onDisappear {
            viewModel?.stop()
        }
    }
}

// MARK: - Content

private struct FriendsContent: View {
    @Bindable var viewModel: FriendsViewModel

    var body: some View {
        Group {
            if viewModel.isLoading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.friends.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "person.2.slash")
                        .font(.system(size: 48)).foregroundStyle(.secondary)
                    Text("No co-chefs yet")
                        .font(.headline).foregroundStyle(.secondary)
                    Text("Find people to follow from the Account tab.")
                        .font(.subheadline).foregroundStyle(.tertiary)
                        .multilineTextAlignment(.center).padding(.horizontal)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(viewModel.friends) { friend in
                    HStack(spacing: 12) {
                        // Avatar
                        ZStack {
                            Circle().fill(Color.accentColor.opacity(0.12)).frame(width: 42, height: 42)
                            Text(String(friend.displayName.prefix(1)).uppercased())
                                .font(.headline).foregroundStyle(Color.accentColor)
                        }

                        Text(friend.displayName).font(.body)

                        Spacer()

                        if viewModel.pendingUnfriend == friend.uid {
                            ProgressView().scaleEffect(0.8)
                        } else {
                            Button("Remove") {
                                viewModel.confirmUnfriendTarget = friend
                            }
                            .font(.subheadline)
                            .buttonStyle(.bordered)
                            .tint(.red)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.plain)
            }
        }
        .confirmationDialog(
            "Remove Co-Chef?",
            isPresented: Binding(
                get: { viewModel.confirmUnfriendTarget != nil },
                set: { if !$0 { viewModel.confirmUnfriendTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let target = viewModel.confirmUnfriendTarget {
                Button("Remove \(target.displayName)", role: .destructive) {
                    viewModel.unfriend(target)
                    viewModel.confirmUnfriendTarget = nil
                }
            }
            Button("Cancel", role: .cancel) { viewModel.confirmUnfriendTarget = nil }
        } message: {
            if let target = viewModel.confirmUnfriendTarget {
                Text("\(target.displayName) will be removed from your co-chefs. They won't be notified.")
            }
        }
    }
}
