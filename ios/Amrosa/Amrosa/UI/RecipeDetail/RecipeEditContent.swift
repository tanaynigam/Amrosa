import SwiftUI

/// F16 inline-edit body: renders the live draft so editing keeps the read-only layout. Every
/// editable element jiggles + outlines (`.editable`) and a tap opens its sheet (`viewModel.editTarget`).
/// Ghost "＋ Add" rows append items in place. Quantities show at 1× / original units.
struct RecipeEditContent: View {
    @Bindable var viewModel: RecipeDetailViewModel

    private func display(_ ing: EditorIngredient) -> String {
        if !ing.quantityDisplay.trimmed.isEmpty { return ing.quantityDisplay }
        if let v = ing.quantityValue {
            let num = (ing.quantityValueMax ?? 0) > v
                ? "\(plainNum(v))–\(plainNum(ing.quantityValueMax!))" : plainNum(v)
            return "\(num) \(ing.quantityUnit)".trimmed
        }
        return ""
    }

    var body: some View {
        // No own ScrollView — embedded inside RecipeDetailContent's shared ScrollView so
        // toggling edit mode preserves scroll position (F16: same surface becomes editable).
        VStack(alignment: .leading, spacing: 0) {

                // ── Details (description + meta) — tap to edit ──
                VStack(alignment: .leading, spacing: 6) {
                    Text(viewModel.editTitle.isEmpty ? "Untitled recipe" : viewModel.editTitle)
                        .font(.title3).fontWeight(.semibold)
                    if !viewModel.editDescription.trimmed.isEmpty {
                        Text(viewModel.editDescription).font(.body).foregroundStyle(.secondary)
                    }
                    HStack(spacing: 12) {
                        if !viewModel.editPrepTime.isEmpty { Text("Prep \(viewModel.editPrepTime)m").font(.footnote).foregroundStyle(.secondary) }
                        if !viewModel.editCookTime.isEmpty { Text("Cook \(viewModel.editCookTime)m").font(.footnote).foregroundStyle(.secondary) }
                        Text(viewModel.editIsRangeYield ? "Yield \(viewModel.editServingsMin)–\(viewModel.editServingsMax)" : "Serves \(viewModel.editBaseServings)")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if !viewModel.editTagsText.trimmed.isEmpty {
                        Text(viewModel.editTagsText).font(.caption).foregroundStyle(.tertiary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .editable(active: true, phase: 0) { viewModel.editTarget = .details }
                .padding(.horizontal, 12).padding(.top, 12)

                // ── Update unit conversions ──
                Button { viewModel.updateConversions() } label: {
                    HStack {
                        if viewModel.isConverting { ProgressView().scaleEffect(0.8) }
                        Label("Update unit conversions", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .font(.subheadline)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isConverting)
                .padding(.horizontal, 16).padding(.top, 12)
                if let msg = viewModel.conversionMessage {
                    Text(msg).font(.caption).foregroundStyle(.secondary)
                        .padding(.horizontal, 16).padding(.top, 4)
                }

                Divider().padding(.horizontal, 16).padding(.vertical, 12)

                // ── Sections ──
                ForEach(Array(viewModel.editSections.enumerated()), id: \.element.id) { sIdx, section in
                    sectionBlock(section, index: sIdx)
                }

                // ── ＋ Add section ──
                ghostRow("Add section", icon: "plus.circle") { viewModel.editTarget = .section(sectionId: nil) }
                    .padding(.top, 8)

                Divider().padding(.horizontal, 16).padding(.vertical, 12)

                // ── Delete recipe ──
                Button(role: .destructive) { viewModel.editTarget = nil; showDeleteConfirm = true } label: {
                    if viewModel.isDeleting { ProgressView() }
                    else { Label("Delete recipe", systemImage: "trash").frame(maxWidth: .infinity) }
                }
                .buttonStyle(.bordered)
                .tint(.red)
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
        }
        .confirmationDialog("Delete this recipe?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { viewModel.deleteRecipe() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This permanently deletes the recipe\(viewModel.recipe.parentRecipeId == nil ? " and its variations" : "").")
        }
    }

    @State private var showDeleteConfirm = false

    @ViewBuilder
    private func sectionBlock(_ section: EditorSection, index: Int) -> some View {
        // Section header (tap to rename / reorder / delete)
        Text(section.name.isEmpty ? "Section \(index + 1)" : section.name)
            .font(.title3).fontWeight(.semibold)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .editable(active: true, phase: index) { viewModel.editTarget = .section(sectionId: section.id) }
            .padding(.horizontal, 12).padding(.top, 8)

        // Ingredients
        Text("Ingredients").font(.caption).fontWeight(.semibold)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 2)
        ForEach(Array(section.ingredients.enumerated()), id: \.element.id) { iIdx, ing in
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "circle").font(.footnote).foregroundStyle(.secondary).padding(.top, 4)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        if !display(ing).isEmpty { Text(display(ing)).fontWeight(.medium) }
                        Text(ing.name.isEmpty ? "Tap to edit" : ing.name)
                    }
                    .font(.body)
                    if ing.isOptional { Text("Optional").font(.caption).foregroundStyle(.tertiary) }
                    if !ing.shoppingNote.trimmed.isEmpty {
                        Label(ing.shoppingNote, systemImage: "lightbulb").font(.caption).foregroundStyle(.tertiary)
                    }
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .editable(active: true, phase: iIdx) { viewModel.editTarget = .ingredient(sectionId: section.id, ingredientId: ing.id) }
            .padding(.horizontal, 12)
        }
        ghostRow("Add ingredient", icon: "plus") { viewModel.editTarget = .ingredient(sectionId: section.id, ingredientId: nil) }

        // Steps
        Text("Steps").font(.caption).fontWeight(.semibold)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 2)
        ForEach(Array(section.steps.enumerated()), id: \.element.id) { stIdx, step in
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    Circle().fill(Color.accentColor.opacity(0.15)).frame(width: 28, height: 28)
                    Text("\(stIdx + 1)").font(.subheadline).foregroundStyle(Color.accentColor)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(step.instruction.isEmpty ? "Tap to edit" : step.instruction)
                        .font(.body).fixedSize(horizontal: false, vertical: true)
                    if !step.ingredientIds.isEmpty {
                        Text(stepIngredientNames(step)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .editable(active: true, phase: stIdx) { viewModel.editTarget = .step(sectionId: section.id, stepId: step.id) }
            .padding(.horizontal, 12)
        }
        ghostRow("Add step", icon: "plus") { viewModel.editTarget = .step(sectionId: section.id, stepId: nil) }
    }

    private func stepIngredientNames(_ step: EditorStep) -> String {
        let all = viewModel.allDraftIngredients
        let names = step.ingredientIds.compactMap { id in all.first { $0.id == id }?.name }
        return names.joined(separator: ", ")
    }

    @ViewBuilder
    private func ghostRow(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.subheadline)
                .foregroundStyle(Color.accentColor)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.accentColor.opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [4])))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16).padding(.top, 4)
    }
}

private func plainNum(_ v: Double) -> String {
    v.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(v)) : String(v)
}
