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
    @State private var detent: PresentationDetent = .large

    /// "Add new" sheets (nil id) commit via the in-form button; existing items edit live.
    private var isAddMode: Bool {
        switch target {
        case .section(let s): return s == nil
        case .ingredient(_, let i): return i == nil
        case .step(_, let st): return st == nil
        case .details: return false
        }
    }

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
                // Add mode: Cancel only (the in-form "Add …" button commits). Edit mode: edits
                // apply live, so "Done" just closes.
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                if !isAddMode {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                }
            }
        }
        // Open fully expanded so every field is visible immediately (matches Android's
        // skipPartiallyExpanded). .medium kept as a secondary drag detent.
        .presentationDetents([.medium, .large], selection: $detent)
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
    let viewModel: RecipeDetailViewModel
    let sectionId: String?
    let dismiss: () -> Void
    @State private var name: String

    init(viewModel: RecipeDetailViewModel, sectionId: String?, dismiss: @escaping () -> Void) {
        self.viewModel = viewModel
        self.sectionId = sectionId
        self.dismiss = dismiss
        let existing = viewModel.editSections.first { $0.id == sectionId }
        _name = State(initialValue: existing?.name ?? "")
    }

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
                    Label("Add section", systemImage: "plus").fontWeight(.semibold)
                }
            }
        }
    }
}

// MARK: - Ingredient

private struct IngredientSheet: View {
    let viewModel: RecipeDetailViewModel
    let sectionId: String
    let ingredientId: String?
    let dismiss: () -> Void

    @State private var name: String
    @State private var qty: String
    @State private var maxQty: String
    @State private var unit: String
    @State private var freeText: String
    @State private var group: String
    @State private var optional: Bool
    @State private var note: String
    @State private var base: EditorIngredient
    @State private var sectionPick: String
    @State private var subPick: String   // "" = no substitute link; else target ingredient id

    init(viewModel: RecipeDetailViewModel, sectionId: String, ingredientId: String?, dismiss: @escaping () -> Void) {
        self.viewModel = viewModel
        self.sectionId = sectionId
        self.ingredientId = ingredientId
        self.dismiss = dismiss
        let b = viewModel.editSections.first { $0.id == sectionId }?
            .ingredients.first { $0.id == ingredientId } ?? EditorIngredient()
        _base = State(initialValue: b)
        _sectionPick = State(initialValue: sectionId)
        // Seed the substitute picker: another member of this ingredient's group, if any.
        let sibling = b.substituteGroupId.flatMap { gid in
            viewModel.editSections.flatMap { $0.ingredients }
                .first { $0.substituteGroupId == gid && $0.id != b.id }?.id
        }
        _subPick = State(initialValue: sibling ?? "")
        _name = State(initialValue: b.name)
        _qty = State(initialValue: b.quantityValue.map(plain) ?? "")
        _maxQty = State(initialValue: b.quantityValueMax.map(plain) ?? "")
        _unit = State(initialValue: b.quantityUnit)
        _freeText = State(initialValue: b.quantityValue == nil ? b.quantityDisplay : "")
        _group = State(initialValue: b.groupLabel)
        _optional = State(initialValue: b.isOptional)
        _note = State(initialValue: b.shoppingNote)
    }

    // Section-agnostic (F21): find the ingredient by id across all sections so moving it between
    // sections mid-edit doesn't break the open sheet.
    private var existing: EditorIngredient? {
        viewModel.editSections.flatMap { $0.ingredients }.first { $0.id == ingredientId }
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

    private func push() { if existing != nil { viewModel.updateIngredientAnywhere(build()) } }

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
            // Group — editable field + a menu suggesting the recipe's existing groups.
            HStack {
                TextField("Group (e.g. Spices)", text: $group).onChange(of: group) { _, _ in push() }
                let existingGroups = Array(Set(viewModel.editSections.flatMap { $0.ingredients }
                    .map { $0.groupLabel.trimmed }.filter { !$0.isEmpty })).sorted()
                if !existingGroups.isEmpty {
                    Menu {
                        ForEach(existingGroups, id: \.self) { g in
                            Button(g) { group = g; push() }
                        }
                    } label: { Image(systemName: "chevron.down.circle").foregroundStyle(.secondary) }
                }
            }
            Toggle("Optional", isOn: $optional).onChange(of: optional) { _, _ in push() }
            TextField("Shopping note (e.g. Amul)", text: $note).onChange(of: note) { _, _ in push() }
        }
        if existing == nil, viewModel.editSections.count > 1 {
            Section("Add to section") {
                Picker("Section", selection: $sectionPick) {
                    ForEach(Array(viewModel.editSections.enumerated()), id: \.element.id) { idx, sec in
                        Text(sec.name.trimmed.isEmpty ? "Section \(idx + 1)" : sec.name).tag(sec.id)
                    }
                }
            }
        }
        // Section selector (F21): move this ingredient to another section, or a new one.
        if let e = existing {
            SectionSelector(viewModel: viewModel, currentSectionId: viewModel.sectionId(ofIngredient: e.id)) { dest in
                viewModel.moveIngredientToSection(e.id, to: dest)
            }
        }
        // Substitute-for picker (existing ingredient): links this ingredient as an interchangeable
        // swap for another, so the reading view shows swap chips under the chosen one.
        if let e = existing {
            let others = viewModel.allDraftIngredients.filter { $0.id != e.id }
            if !others.isEmpty {
                Section("Substitute for") {
                    Picker("Substitute for", selection: $subPick) {
                        Text("None").tag("")
                        ForEach(others) { ing in Text(ing.name).tag(ing.id) }
                    }
                    .onChange(of: subPick) { _, v in
                        viewModel.linkSubstitute(ingredientId: e.id, targetId: v.isEmpty ? nil : v)
                    }
                }
            }
        }
        Section {
            if existing == nil {
                Button { viewModel.addIngredient(to: sectionPick, build()); dismiss() } label: {
                    Label("Add ingredient", systemImage: "plus").fontWeight(.semibold)
                }
                .disabled(name.trimmed.isEmpty)
            } else if let e = existing {
                let cur = viewModel.sectionId(ofIngredient: e.id) ?? sectionId
                Button { viewModel.moveIngredient(in: cur, e.id, by: -1) } label: { Label("Move up", systemImage: "arrow.up") }
                Button { viewModel.moveIngredient(in: cur, e.id, by: 1) } label: { Label("Move down", systemImage: "arrow.down") }
                Button(role: .destructive) { viewModel.deleteIngredientAnywhere(e.id); dismiss() } label: {
                    Label("Delete ingredient", systemImage: "trash")
                }
            }
        }
    }
}

// MARK: - Step

private struct StepSheet: View {
    let viewModel: RecipeDetailViewModel
    let sectionId: String
    let stepId: String?
    let dismiss: () -> Void

    @State private var instruction: String
    @State private var ids: Set<String>
    @State private var sectionPick: String

    init(viewModel: RecipeDetailViewModel, sectionId: String, stepId: String?, dismiss: @escaping () -> Void) {
        self.viewModel = viewModel
        self.sectionId = sectionId
        self.stepId = stepId
        self.dismiss = dismiss
        let e = viewModel.editSections.first { $0.id == sectionId }?.steps.first { $0.id == stepId }
        _instruction = State(initialValue: e?.instruction ?? "")
        _ids = State(initialValue: Set(e?.ingredientIds ?? []))
        _sectionPick = State(initialValue: sectionId)
    }

    // Section-agnostic (F21): find the step by id across all sections.
    private var existing: EditorStep? {
        viewModel.editSections.flatMap { $0.steps }.first { $0.id == stepId }
    }

    private func push() {
        if let e = existing {
            var u = e
            u.instruction = instruction
            u.ingredientIds = Array(ids)
            viewModel.updateStepAnywhere(u)
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
        if existing == nil, viewModel.editSections.count > 1 {
            Section("Add to section") {
                Picker("Section", selection: $sectionPick) {
                    ForEach(Array(viewModel.editSections.enumerated()), id: \.element.id) { idx, sec in
                        Text(sec.name.trimmed.isEmpty ? "Section \(idx + 1)" : sec.name).tag(sec.id)
                    }
                }
            }
        }
        // Section selector (F21): move this step to another section, or a new one.
        if let e = existing {
            SectionSelector(viewModel: viewModel, currentSectionId: viewModel.sectionId(ofStep: e.id)) { dest in
                viewModel.moveStepToSection(e.id, to: dest)
            }
        }
        Section {
            if existing == nil {
                Button {
                    viewModel.addStep(to: sectionPick, EditorStep(instruction: instruction, ingredientIds: Array(ids)))
                    dismiss()
                } label: {
                    Label("Add step", systemImage: "plus").fontWeight(.semibold)
                }
                .disabled(instruction.trimmed.isEmpty)
            } else if let e = existing {
                Button(role: .destructive) { viewModel.deleteStepAnywhere(e.id); dismiss() } label: {
                    Label("Delete step", systemImage: "trash")
                }
            }
        }
    }
}

// MARK: - Section selector (F21) — move an item to an existing or new section

private struct SectionSelector: View {
    let viewModel: RecipeDetailViewModel
    let currentSectionId: String?
    let onPick: (String) -> Void

    private func name(_ sec: EditorSection, _ idx: Int) -> String {
        sec.name.trimmed.isEmpty ? "Section \(idx + 1)" : sec.name
    }
    private var currentName: String {
        guard let id = currentSectionId,
              let idx = viewModel.editSections.firstIndex(where: { $0.id == id }) else { return "—" }
        return name(viewModel.editSections[idx], idx)
    }

    var body: some View {
        Section("Section") {
            Menu {
                ForEach(Array(viewModel.editSections.enumerated()), id: \.element.id) { idx, sec in
                    Button { onPick(sec.id) } label: {
                        if sec.id == currentSectionId { Label(name(sec, idx), systemImage: "checkmark") }
                        else { Text(name(sec, idx)) }
                    }
                }
                Divider()
                Button { onPick(viewModel.addSectionReturningId()) } label: {
                    Label("New section", systemImage: "plus")
                }
            } label: {
                LabeledContent("In section", value: currentName)
            }
        }
    }
}
