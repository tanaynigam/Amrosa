import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class SharedInboxViewModel {
    var items: [ReceivedRecipeSummary] = []
    var isLoading = true
    private var streamTask: Task<Void, Never>? = nil

    private let socialRepository: SocialRepository

    init(socialRepository: SocialRepository) {
        self.socialRepository = socialRepository
    }

    func start() {
        guard streamTask == nil else { return }
        streamTask = Task {
            for await batch in socialRepository.receivedRecipesSummaryStream() {
                guard !Task.isCancelled else { break }
                items = batch
                isLoading = false
            }
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
    }
}

// MARK: - View

struct SharedInboxView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: SharedInboxViewModel?
    @State private var selectedShareId: String? = nil

    var body: some View {
        Group {
            if let vm = viewModel {
                SharedInboxContent(
                    viewModel: vm,
                    selectedShareId: $selectedShareId
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Shared Recipes")
        .navigationDestination(item: $selectedShareId) { shareId in
            ReceivedRecipeView(shareId: shareId)
        }
        .onAppear {
            if viewModel == nil {
                viewModel = SharedInboxViewModel(socialRepository: container.socialRepository)
            }
            viewModel?.start()
        }
        .onDisappear {
            viewModel?.stop()
        }
    }
}

// MARK: - Content

private struct SharedInboxContent: View {
    @Bindable var viewModel: SharedInboxViewModel
    @Binding var selectedShareId: String?

    var body: some View {
        Group {
            if viewModel.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.items.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "tray")
                        .font(.system(size: 48))
                        .foregroundStyle(.secondary)
                    Text("No recipes shared with you yet")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                    Text("When someone sends you a recipe, it will appear here.")
                        .font(.subheadline)
                        .foregroundStyle(.tertiary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.items) { item in
                            Button {
                                selectedShareId = item.shareId
                            } label: {
                                SharedRecipeCard(item: item)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
    }
}

// MARK: - Card (same visual language as My Recipes cards)

struct SharedRecipeCard: View {
    let item: ReceivedRecipeSummary

    private var timeLabel: String {
        var parts: [String] = []
        if let p = item.prepTimeMinutes { parts.append("Prep \(p)min") }
        if let c = item.cookTimeMinutes { parts.append("Cook \(c)min") }
        return parts.joined(separator: "  ·  ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(item.title)
                .font(.title3).fontWeight(.semibold)
                .lineLimit(2)
                .foregroundStyle(.primary)

            if !timeLabel.isEmpty {
                Text(timeLabel)
                    .font(.caption).foregroundStyle(.secondary)
            }

            if !item.tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(item.tags, id: \.self) { tag in
                            Text(tag)
                                .font(.caption2)
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Color(.tertiarySystemFill))
                                .clipShape(Capsule())
                        }
                    }
                }
            }

            // Author + "from sender" + timestamp
            HStack(spacing: 4) {
                Image(systemName: "person")
                    .font(.caption2).foregroundStyle(.secondary)
                Text(item.authorDisplayName)
                    .font(.caption).fontWeight(.medium).foregroundStyle(.secondary)
                if item.fromDisplayName != item.authorDisplayName {
                    Text("· from \(item.fromDisplayName)")
                        .font(.caption).foregroundStyle(.tertiary)
                }
                Spacer()
                Text(item.sharedAt.relativeString())
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
