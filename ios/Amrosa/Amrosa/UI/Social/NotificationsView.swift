import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class NotificationsViewModel {
    var notifications: [SocialNotification] = []
    var isLoading: Bool = true

    private let socialRepository: SocialRepository
    private var streamTask: Task<Void, Never>? = nil

    init(socialRepository: SocialRepository) {
        self.socialRepository = socialRepository
        start()
    }

    private func start() {
        streamTask = Task {
            for await batch in socialRepository.notificationsStream() {
                guard !Task.isCancelled else { break }
                notifications = batch
                isLoading = false
            }
        }
    }

    func markRead(_ notifId: String) {
        Task { await socialRepository.markNotificationRead(notifId: notifId) }
    }

    func markAllRead() {
        Task { await socialRepository.markAllNotificationsRead() }
    }

    deinit {
        streamTask?.cancel()
    }
}

// MARK: - View

struct NotificationsView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: NotificationsViewModel?

    let onFollowNotification: () -> Void
    let onRecipeShared: (String) -> Void

    var body: some View {
        Group {
            if let vm = viewModel {
                NotificationsContent(
                    viewModel: vm,
                    onFollowNotification: onFollowNotification,
                    onRecipeShared: onRecipeShared
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Notifications")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if viewModel == nil {
                viewModel = NotificationsViewModel(socialRepository: container.socialRepository)
            }
        }
    }
}

// MARK: - Content

private struct NotificationsContent: View {
    @Bindable var viewModel: NotificationsViewModel
    let onFollowNotification: () -> Void
    let onRecipeShared: (String) -> Void

    var hasUnread: Bool {
        viewModel.notifications.contains { !$0.read }
    }

    var body: some View {
        Group {
            if viewModel.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.notifications.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "bell.slash")
                        .font(.system(size: 48))
                        .foregroundStyle(.secondary)
                    Text("No notifications yet")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(viewModel.notifications) { notif in
                    NotificationRow(notif: notif)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            viewModel.markRead(notif.id)
                            switch notif.type {
                            case "follow_request", "follow_accepted":
                                onFollowNotification()
                            case "recipe_shared":
                                if let shareId = notif.shareId {
                                    onRecipeShared(shareId)
                                }
                            default:
                                break
                            }
                        }
                }
                .listStyle(.plain)
            }
        }
        .toolbar {
            if hasUnread {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Mark all read") { viewModel.markAllRead() }
                        .font(.subheadline)
                }
            }
        }
    }
}

// MARK: - Row

private struct NotificationRow: View {
    let notif: SocialNotification

    var iconName: String {
        switch notif.type {
        case "follow_request":  return "person.badge.plus"
        case "follow_accepted": return "person.fill.checkmark"
        case "recipe_shared":   return "fork.knife"
        default:                return "bell"
        }
    }

    var iconColor: Color {
        switch notif.type {
        case "follow_request":  return .blue
        case "follow_accepted": return .green
        case "recipe_shared":   return .orange
        default:                return .gray
        }
    }

    var actionText: String {
        switch notif.type {
        case "follow_request":
            return "sent you a follow request"
        case "follow_accepted":
            return "accepted your follow request"
        case "recipe_shared":
            if let name = notif.recipeName {
                return "shared \"\(name)\" with you"
            }
            return "shared a recipe with you"
        default:
            return ""
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // Icon circle
            ZStack {
                Circle()
                    .fill(iconColor.opacity(0.15))
                    .frame(width: 40, height: 40)
                Image(systemName: iconName)
                    .font(.subheadline)
                    .foregroundStyle(iconColor)
            }

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Text(notif.fromDisplayName)
                        .fontWeight(notif.read ? .regular : .semibold)
                    Text(actionText)
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)

                Text(notif.createdAt.relativeString())
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            Spacer()

            // Unread dot
            if !notif.read {
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: 8, height: 8)
                    .padding(.top, 6)
            }
        }
        .padding(.vertical, 6)
    }
}
