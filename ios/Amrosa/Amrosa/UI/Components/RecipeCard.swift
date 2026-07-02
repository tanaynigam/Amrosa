import SwiftUI

struct RecipeCard: View {
    let recipe: RecipeModel
    var showNeedsReviewBanner: Bool = false
    /// F20: published like count for this recipe (from `getAuthorLikeCounts`); shown when > 0.
    var likeCount: Int = 0
    /// F19: a background save for this recipe is in flight → show a small spinner.
    var isSaving: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if showNeedsReviewBanner && recipe.needsReview {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text("Needs review — tap to confirm")
                        .font(.caption)
                        .foregroundStyle(.orange)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.orange.opacity(0.12))
            }

            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(recipe.title)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    if let desc = recipe.recipeDescription {
                        Text(desc)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }

                    HStack(spacing: 12) {
                        if let prep = recipe.prepTimeMinutes ?? recipe.cookTimeMinutes {
                            Label(prep.timeDisplayString, systemImage: "clock")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Label(recipe.displayYield, systemImage: "person.2")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        if likeCount > 0 {
                            Label(likeCount.compactCount, systemImage: "heart.fill")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.top, 2)

                    if !recipe.tags.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(recipe.tags, id: \.self) { tag in
                                    TagChip(text: tag)
                                }
                            }
                        }
                        .padding(.top, 4)
                    }

                    // v2 author label — Tab 1 is always my recipes: "me" / "Imported by me"
                    Text(recipe.isImported ? "Imported by me" : "me")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .padding(.top, 2)
                }
                Spacer(minLength: 0)

                if isSaving {
                    ProgressView()
                }

                if recipe.imageUrl != nil {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color(.tertiarySystemBackground))
                        .frame(width: 80, height: 80)
                        .overlay {
                            Image(systemName: "photo")
                                .foregroundStyle(.quaternary)
                        }
                }
            }
            .padding(12)
        }
        .amrosaCard()
    }
}

struct TagChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2)
            .fontWeight(.medium)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.accentColor.opacity(0.12))
            .foregroundStyle(Color.accentColor)
            .clipShape(Capsule())
    }
}
