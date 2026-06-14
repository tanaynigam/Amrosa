import SwiftUI

/// What the open edit sheet is editing. A nil id means "add new" (committed by the sheet's Add button).
enum EditTarget: Identifiable, Hashable {
    case details
    case section(sectionId: String?)
    case ingredient(sectionId: String, ingredientId: String?)
    case step(sectionId: String, stepId: String?)

    var id: String {
        switch self {
        case .details: return "details"
        case .section(let s): return "section-\(s ?? "new")"
        case .ingredient(let s, let i): return "ingredient-\(s)-\(i ?? "new")"
        case .step(let s, let st): return "step-\(s)-\(st ?? "new")"
        }
    }
}

private func plain(_ v: Double) -> String {
    v.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(v)) : String(v)
}

/// Build the display string from numeric amount + unit (+ optional range), falling back to free text.
private func deriveDisplay(value: Double?, max: Double?, unit: String, fallback: String) -> String {
    guard let value else { return fallback.trimmed }
    let num = (max != nil && max! > value) ? "\(plain(value))–\(plain(max!))" : plain(value)
    return "\(num) \(unit.trimmed)".trimmed
}

// MARK: - Host

/// Presented via `.sheet(item: $viewModel.editTarget)`. Routes to the right form.
struct EditSheetHost: View {
    let target: EditTarget
    @Bindable var viewModel: RecipeDetailViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                switch target {
                case .details:
                    DetailsSheet(viewModel: viewModel)
                case .section(let sectionId):
                    SectionSheet(viewModel: viewModel, sectionId: sectionId, dismiss: { dismiss() })
                case .ingredient(let sectionId, let ingredientId):
                    IngredientSheet(viewModel: viewModel, sectionId: sectionId, ingredientId: ingredientId, dismiss: { dismiss() })
                case .step(let sectionId, let stepId):
                    StepSheet(viewModel: viewModel, sectionId: sectionId, stepId: stepId, dismiss: { dismiss() })
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Details

private struct DetailsSheet: View {
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        Section("Recipe details") {
            TextField("Title", text: $viewModel.editTitle)
            TextField("Description", text: $viewModel.editDescription, axis: .vertical).lineLimit(2...4)
            Picker("Author", selection: $viewModel.editIsPersonalAuthor) {
                Text("Imported").tag(false)
                Text("Personal — \(viewModel.personalAuthorName)").tag(true)
            }
            if viewModel.editIsVariant {
                TextField("Variation name", text: Binding(
                    get: { viewModel.editVariantName },
                    set: { viewModel.setVariantName($0) }
                ))
            }
        }
        Section("Time & yield") {
            TextField("Prep (min)", text: $viewModel.editPrepTime).keyboardType(.numberPad)
            TextField("Cook (min)", text: $viewModel.editCookTime).keyboardType(.numberPad)
            Toggle("Range yield (e.g. 15–20)", isOn: $viewModel.editIsRangeYield)
            if viewModel.editIsRangeYield {
                TextField("Min", text: $viewModel.editServingsMin).keyboardType(.numberPad)
                TextField("Max", text: $viewModel.editServingsMax).keyboardType(.numberPad)
            } else {
                TextField("Servings", text: $viewModel.editBaseServings).keyboardType(.numberPad)
            }
        }
        Section("Tags & sources") {
            TextField("Tags (comma-separated)", text: $viewModel.editTagsText)
            TextField("Source URLs (one per line)", text: $viewModel.editSourceUrlsText, axis: .vertical).lineLimit(1...4)
        }
    }
}

// MARK: - Section

private struct SectionSheet: View {
    @Bindable var viewModel: RecipeDetailViewModel
    let sectionId: String?
    let dismiss: () -> Void
    @State private var name = ""
    @State private var seeded = false

    private var existing: EditorSection? {
        viewModel.editSections.first { $0.id == sectionId }
    }

    var body: some View {
        Section(existing == nil ? "Add section" : "Edit section") {
            TextField("Section name", text: $name)
                .onChange(of: name) { _, v in
                    if let e = existing { viewModel.updateSectionName(e.id, v) }
                }
        }
        if let e = existing {
            Section {
                Button { viewModel.moveSection(e.id, by: -1) } label: { Label("Move up", systemImage: "arrow.up") }
                Button { viewModel.moveSection(e.id, by: 1) } label: { Label("Move down", systemImage: "arrow.down") }
                if viewModel.editSections.count > 1 {
                    Button(role: .destructive) { viewModel.deleteSection(e.id); dismiss() } label: {
                        Label("Delete section", systemImage: "trash")
                    }
                }
            }
        } else {
            Section {
                Button { viewModel.addSection(name: name); dismiss() } label: {
                    Label("Add section", systemImage: "plus")
                }
            }
        }
        Color.clear.onAppear { if !seeded { name = existing?.name ?? ""; seeded = true } }
    }
}

// MARK: - Ingredient

private struct IngredientSheet: View {
    @Bindable var viewModel: RecipeDetailViewModel
    let sectionId: String
    let ingredientId: String?
    let dismiss: () -> Void

    @State private var name = ""
    @State private var qty = ""
    @State private var maxQty = ""
    @State private var unit = ""
    @State private var freeText = ""
    @State private var group = ""
    @State private var optional = false
    @State private var note = ""
    @State private var base = EditorIngredient()
    @State private var seeded = false

    private var existing: EditorIngredient? {
        viewModel.editSections.first { $0.id == sectionId }?.ingredients.first { $0.id == ingredientId }
    }

    private func build() -> EditorIngredient {
        var ing = base
        let v = Double(qty)
        let m = Double(maxQty)
        ing.name = name
        ing.quantityValue = v
        ing.quantityValueMax = m
        ing.quantityUnit = unit
        ing.quantityDisplay = deriveDisplay(value: v, max: m, unit: unit, fallback: freeText)
        ing.groupLabel = group
        ing.isOptional = optional
        ing.shoppingNote = note
        return ing
    }

    private func push() { if existing != nil { viewModel.updateIngredient(in: sectionId, build()) } }

    var body: some View {
        Section(existing == nil ? "Add ingredient" : "Edit ingredient") {
            TextField("Ingredient", text: $name).onChange(of: name) { _, _ in push() }
            HStack {
                TextField("Quantity", text: $qty).keyboardType(.decimalPad).onChange(of: qty) { _, _ in push() }
                Text("to").foregroundStyle(.secondary)
                TextField("max", text: $maxQty).keyboardType(.decimalPad).onChange(of: maxQty) { _, _ in push() }
            }
            TextField("Unit (cup, g, clove…)", text: $unit).onChange(of: unit) { _, _ in push() }
            if qty.isEmpty {
                TextField("Or free text (e.g. to taste)", text: $freeText).onChange(of: freeText) { _, _ in push() }
            }
            TextField("Group (e.g. Spices)", text: $group).onChange(of: group) { _, _ in push() }
            Toggle("Optional", isOn: $optional).onChange(of: optional) { _, _ in push() }
            TextField("Shopping note (e.g. Amul)", text: $note).onChange(of: note) { _, _ in push() }
        }
        Section {
            if existing == nil {
                Button { viewModel.addIngredient(to: sectionId, build()); dismiss() } label: {
                    Label("Add ingredient", systemImage: "plus")
                }
                .disabled(name.trimmed.isEmpty)
            } else if let e = existing {
                Button(role: .destructive) { viewModel.deleteIngredient(in: sectionId, e.id); dismiss() } label: {
                    Label("Delete ingredient", systemImage: "trash")
                }
            }
        }
        Color.clear.onAppear {
            guard !seeded else { return }
            seeded = true
            let b = existing ?? EditorIngredient()
            base = b
            name = b.name
            qty = b.quantityValue.map(plain) ?? ""
            maxQty = b.quantityValueMax.map(plain) ?? ""
            unit = b.quantityUnit
            freeText = b.quantityValue == nil ? b.quantityDisplay : ""
            group = b.groupLabel
            optional = b.isOptional
            note = b.shoppingNote
        }
    }
}

// MARK: - Step

private struct StepSheet: View {
    @Bindable var viewModel: RecipeDetailViewModel
    let sectionId: String
    let stepId: String?
    let dismiss: () -> Void

    @State private var instruction = ""
    @State private var ids: Set<String> = []
    @State private var seeded = false

    private var existing: EditorStep? {
        viewModel.editSections.first { $0.id == sectionId }?.steps.first { $0.id == stepId }
    }

    private func push() {
        if let e = existing {
            var u = e
            u.instruction = instruction
            u.ingredientIds = Array(ids)
            viewModel.updateStep(in: sectionId, u)
        }
    }

    var body: some View {
        Section(existing == nil ? "Add step" : "Edit step") {
            TextField("Instruction", text: $instruction, axis: .vertical)
                .lineLimit(2...8)
                .onChange(of: instruction) { _, _ in push() }
        }
        let allIngredients = viewModel.allDraftIngredients
        if !allIngredients.isEmpty {
            Section("Uses ingredients") {
                ForEach(allIngredients) { ing in
                    Button {
                        if ids.contains(ing.id) { ids.remove(ing.id) } else { ids.insert(ing.id) }
                        push()
                    } label: {
                        HStack {
                            Image(systemName: ids.contains(ing.id) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(ids.contains(ing.id) ? Color.accentColor : Color.secondary)
                            Text(ing.name).foregroundStyle(.primary)
                            Spacer()
                        }
                    }
                }
            }
        }
        Section {
            if existing == nil {
                Button {
                    viewModel.addStep(to: sectionId, EditorStep(instruction: instruction, ingredientIds: Array(ids)))
                    dismiss()
                } label: {
                    Label("Add step", systemImage: "plus")
                }
                .disabled(instruction.trimmed.isEmpty)
            } else if let e = existing {
                Button(role: .destructive) { viewModel.deleteStep(in: sectionId, e.id); dismiss() } label: {
                    Label("Delete step", systemImage: "trash")
                }
            }
        }
        Color.clear.onAppear {
            guard !seeded else { return }
            seeded = true
            instruction = existing?.instruction ?? ""
            ids = Set(existing?.ingredientIds ?? [])
        }
    }
}
