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
        .navigationTitle("Shared with Me")
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
                List(viewModel.items) { item in
                    Button {
                        selectedShareId = item.shareId
                    } label: {
                        SharedInboxRow(item: item)
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
            }
        }
    }
}

// MARK: - Row

private struct SharedInboxRow: View {
    let item: ReceivedRecipeSummary

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "tray.fill")
                .foregroundStyle(Color.accentColor)
                .font(.title3)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(.body)
                    .fontWeight(.medium)
                    .foregroundStyle(.primary)
                Text("From \(item.fromDisplayName) · \(item.sharedAt.relativeString())")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 6)
    }
}
