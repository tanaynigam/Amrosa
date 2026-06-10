import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class ShoppingListViewModel {
    var recipe: RecipeModel?
    var selectedServings: Int
    var scaleAnchorQty: Double?
    var unitMode: UnitMode = .original
    var checkedKeys: Set<String> = []
    var lines: [ShoppingLine] = []

    private let repository: RecipeRepository
    private let recipeId: String

    init(repository: RecipeRepository, recipeId: String, initialServings: Int?, initialAnchorQty: Double?) {
        self.repository = repository
        self.recipeId = recipeId
        self.selectedServings = initialServings ?? 1
        self.scaleAnchorQty = initialAnchorQty
        load()
    }

    private func load() {
        recipe = try? repository.fetchRecipe(id: recipeId)
        if let r = recipe {
            if selectedServings <= 0 { selectedServings = r.baseServings }
            if scaleAnchorQty == nil, let anchorId = r.scaleIngredientId {
                scaleAnchorQty = r.ingredients.first(where: { $0.id == anchorId })?.quantityValue
            }
        }
        checkedKeys = (try? repository.checkedShoppingItems(recipeId: recipeId)) ?? []
        rebuildLines()
    }

    var usesAnchorScaling: Bool { recipe?.scaleIngredientId != nil }

    var scaleFactor: Double {
        guard let r = recipe else { return 1.0 }
        if let anchorQty = scaleAnchorQty, let anchorId = r.scaleIngredientId {
            let baseQty = r.ingredients.first(where: { $0.id == anchorId })?.quantityValue ?? 0
            return baseQty == 0 ? 1.0 : anchorQty / baseQty
        }
        return r.baseServings == 0 ? 1.0 : Double(selectedServings) / Double(r.baseServings)
    }

    var yieldDisplay: String {
        guard let r = recipe else { return "\(selectedServings)" }
        if let min = r.baseServingsMin, let max = r.baseServingsMax {
            let lo = Int(Double(min) * scaleFactor)
            let hi = Int(Double(max) * scaleFactor)
            return lo == hi ? "\(lo)" : "\(lo)–\(hi)"
        }
        return "\(Int(Double(r.baseServings) * scaleFactor))"
    }

    var hasConversions: Bool {
        recipe?.ingredients.contains { $0.hasConversionData } == true
    }

    /// Ingredients that belong on the list: all, but only one member per substitute group.
    private var shoppingIngredients: [IngredientModel] {
        guard let r = recipe else { return [] }
        let sorted = r.ingredients.sorted { $0.orderIndex < $1.orderIndex }
        return sorted.filter { ing in
            guard let gid = ing.substituteGroupId else { return true }
            return sorted.first(where: { $0.substituteGroupId == gid })?.id == ing.id
        }
    }

    func rebuildLines() {
        lines = ShoppingAggregator.build(
            ingredients: shoppingIngredients,
            scaleFactor: scaleFactor,
            unitMode: unitMode
        )
    }

    func setUnit(_ unit: UnitMode) {
        unitMode = unit
        rebuildLines()
    }

    func adjustScale(_ delta: Int) {
        guard let r = recipe else { return }
        if r.scaleIngredientId != nil, let anchorQty = scaleAnchorQty {
            scaleAnchorQty = max(r.scaleStep, anchorQty + Double(delta) * r.scaleStep)
        } else {
            selectedServings = max(1, selectedServings + delta)
        }
        rebuildLines()
    }

    func toggle(_ itemKey: String) {
        let checked = checkedKeys.contains(itemKey)
        try? repository.setShoppingChecked(recipeId: recipeId, itemKey: itemKey, checked: !checked)
        if checked { checkedKeys.remove(itemKey) } else { checkedKeys.insert(itemKey) }
    }

    func resetChecks() {
        try? repository.clearShoppingChecks(recipeId: recipeId)
        checkedKeys = []
    }
}

// MARK: - View

struct ShoppingListView: View {
    let recipeId: String
    var initialServings: Int? = nil
    var initialAnchorQty: Double? = nil

    @Environment(AppContainer.self) private var container
    @State private var viewModel: ShoppingListViewModel?
    @State private var showResetConfirm = false

    var body: some View {
        Group {
            if let vm = viewModel {
                ShoppingListContent(viewModel: vm)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Shopping List")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if viewModel?.checkedKeys.isEmpty == false {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Reset") { showResetConfirm = true }
                }
            }
        }
        .confirmationDialog("Reset checklist?", isPresented: $showResetConfirm, titleVisibility: .visible) {
            Button("Reset", role: .destructive) { viewModel?.resetChecks() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Uncheck every item on this shopping list.")
        }
        .onAppear {
            if viewModel == nil {
                viewModel = ShoppingListViewModel(
                    repository: container.recipeRepository,
                    recipeId: recipeId,
                    initialServings: initialServings,
                    initialAnchorQty: initialAnchorQty
                )
            }
        }
    }
}

// MARK: - Content

private struct ShoppingListContent: View {
    @Bindable var viewModel: ShoppingListViewModel

    var body: some View {
        Group {
            if let recipe = viewModel.recipe {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        // ── Recipe title + yield + unit toggle ─────────────
                        Text(recipe.title)
                            .font(.headline)
                            .padding(.horizontal, 16).padding(.top, 12)

                        HStack {
                            HStack(spacing: 4) {
                                Text("Yield").font(.footnote).foregroundStyle(.secondary)
                                Button { viewModel.adjustScale(-1) } label: {
                                    Image(systemName: "minus").frame(width: 32, height: 32)
                                }
                                .buttonStyle(.plain)
                                Text(viewModel.yieldDisplay)
                                    .font(.title3).fontWeight(.semibold).frame(minWidth: 36)
                                Button { viewModel.adjustScale(1) } label: {
                                    Image(systemName: "plus").frame(width: 32, height: 32)
                                }
                                .buttonStyle(.plain)
                            }
                            Spacer()
                            if viewModel.hasConversions {
                                Picker("Units", selection: Binding(
                                    get: { viewModel.unitMode },
                                    set: { viewModel.setUnit($0) }
                                )) {
                                    ForEach(UnitMode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                                }
                                .pickerStyle(.segmented)
                                .frame(width: 180)
                            }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 8)

                        Divider().padding(.horizontal, 16)

                        // ── Combined checklist ─────────────────────────────
                        ForEach(viewModel.lines) { line in
                            ShoppingLineRow(
                                line: line,
                                isChecked: viewModel.checkedKeys.contains(line.key),
                                onToggle: { viewModel.toggle(line.key) }
                            )
                        }

                        Spacer(minLength: 32)
                    }
                }
            } else {
                Text("Recipe not found").foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Row

private struct ShoppingLineRow: View {
    let line: ShoppingLine
    let isChecked: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isChecked ? Color.accentColor : Color.secondary)
                    .font(.title3)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        if !line.quantity.isEmpty {
                            Text(line.quantity).fontWeight(.medium)
                        }
                        Text(line.name)
                    }
                    .font(.body)
                    .strikethrough(isChecked)
                    .foregroundStyle(isChecked ? Color.secondary : Color.primary)

                    if let note = line.note {
                        Text("💡 \(note)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
