import SwiftUI

struct RecipeReviewSheet: View {
    let parsed: ParsedRecipeData
    let primaryLabel: String
    let secondaryLabel: String
    var isSecondaryLoading: Bool = false
    let onPrimary: () -> Void
    let onSecondary: () -> Void
    let onDismiss: () -> Void
    var onEdit: (() -> Void)? = nil
    var isOwnRecipe: Bool = false
    var onIsOwnRecipeChange: ((Bool) -> Void)? = nil

    @State private var parseNotesDismissed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Parse notes banner
                    if let notes = parsed.parseNotes, !notes.isEmpty, !parseNotesDismissed {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "info.circle.fill")
                                .foregroundStyle(Color.accentColor)
                            Text(notes)
                                .font(.subheadline)
                            Spacer()
                            Button {
                                parseNotesDismissed = true
                            } label: {
                                Image(systemName: "xmark")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding()
                        .background(Color(.tertiarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .padding(.horizontal)
                    }

                    // Author toggle (import flow only)
                    if let onChange = onIsOwnRecipeChange {
                        Picker("Author", selection: Binding(
                            get: { isOwnRecipe },
                            set: { onChange($0) }
                        )) {
                            Text("Imported").tag(false)
                            Text("My Recipe").tag(true)
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal)
                    }

                    // Recipe preview
                    VStack(alignment: .leading, spacing: 16) {
                        Text(parsed.title)
                            .font(.title2)
                            .fontWeight(.bold)
                            .padding(.horizontal)

                        if let desc = parsed.description {
                            Text(desc)
                                .font(.body)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal)
                        }

                        // Meta
                        HStack(spacing: 20) {
                            if let prep = parsed.prepTimeMinutes {
                                Label(prep.timeDisplayString, systemImage: "clock")
                            }
                            if let cook = parsed.cookTimeMinutes {
                                Label(cook.timeDisplayString, systemImage: "flame")
                            }
                            Label("\(parsed.baseServings)", systemImage: "person.2")
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)

                        if !parsed.tags.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack {
                                    ForEach(parsed.tags, id: \.self) { tag in TagChip(text: tag) }
                                }
                                .padding(.horizontal)
                            }
                        }

                        Divider()

                        // Sections + ingredients preview
                        if !parsed.sections.isEmpty {
                            ForEach(parsed.sections.sorted { $0.orderIndex < $1.orderIndex }) { section in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(section.name)
                                        .font(.headline)
                                        .padding(.horizontal)

                                    let sectionIngredients = parsed.ingredients
                                        .filter { $0.sectionId == section.id }
                                        .sorted { $0.orderIndex < $1.orderIndex }

                                    ForEach(sectionIngredients) { ing in
                                        HStack {
                                            Text(ing.quantityDisplay ?? "")
                                                .fontWeight(.medium)
                                            Text(ing.name)
                                            if ing.isOptional {
                                                Text("(optional)")
                                                    .foregroundStyle(.tertiary)
                                            }
                                        }
                                        .font(.subheadline)
                                        .padding(.horizontal)
                                    }

                                    let sectionSteps = parsed.steps
                                        .filter { $0.sectionId == section.id }
                                        .sorted { $0.orderIndex < $1.orderIndex }

                                    ForEach(Array(sectionSteps.enumerated()), id: \.element.id) { idx, step in
                                        HStack(alignment: .top, spacing: 10) {
                                            Text("\(idx + 1).")
                                                .fontWeight(.semibold)
                                                .foregroundStyle(.secondary)
                                            Text(step.instruction)
                                        }
                                        .font(.subheadline)
                                        .padding(.horizontal)
                                    }
                                }
                            }
                        } else {
                            // No sections — flat list
                            ForEach(parsed.ingredients.sorted { $0.orderIndex < $1.orderIndex }) { ing in
                                HStack {
                                    Text(ing.quantityDisplay ?? "")
                                        .fontWeight(.medium)
                                    Text(ing.name)
                                }
                                .font(.subheadline)
                                .padding(.horizontal)
                            }
                            ForEach(Array(parsed.steps.sorted { $0.orderIndex < $1.orderIndex }.enumerated()), id: \.element.id) { idx, step in
                                HStack(alignment: .top, spacing: 10) {
                                    Text("\(idx + 1).")
                                        .fontWeight(.semibold)
                                        .foregroundStyle(.secondary)
                                    Text(step.instruction)
                                }
                                .font(.subheadline)
                                .padding(.horizontal)
                            }
                        }
                    }

                    // Action buttons
                    VStack(spacing: 12) {
                        Button {
                            onPrimary()
                        } label: {
                            Text(primaryLabel)
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                        }
                        .buttonStyle(.borderedProminent)

                        Button {
                            onSecondary()
                        } label: {
                            Group {
                                if isSecondaryLoading {
                                    ProgressView()
                                } else {
                                    Text(secondaryLabel)
                                        .fontWeight(.medium)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 14)
                                }
                            }
                        }
                        .buttonStyle(.bordered)
                        .disabled(isSecondaryLoading)
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 24)
                }
            }
            .navigationTitle("Review Recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Dismiss") { onDismiss() }
                }
                if let edit = onEdit {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            edit()
                        } label: {
                            Image(systemName: "pencil")
                        }
                    }
                }
            }
        }
    }
}

extension ParsedSection: Identifiable {}
extension ParsedIngredient: Identifiable {}
extension ParsedStep: Identifiable {}
