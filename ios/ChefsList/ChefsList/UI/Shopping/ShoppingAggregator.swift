import Foundation

/// One combined line on the shopping list. `key` is the persisted checked-state key.
struct ShoppingLine: Identifiable {
    let key: String
    let name: String
    let quantity: String
    let note: String?

    var id: String { key }
}

/// Combines a recipe's ingredients into a flat shopping list: ingredients with the same
/// (normalized) name are merged into one line with their quantities summed. Summing happens
/// per unit within the active `UnitMode` — Metric mode reduces most items to clean g/ml; when
/// units in a group don't match they're joined with " + ". Non-numeric amounts ("to taste")
/// are carried through as-is. Author shopping notes are aggregated onto the line.
enum ShoppingAggregator {

    /// Mirror of the backend `normalizeKey` so learned keys line up conceptually.
    static func normalizeKey(_ name: String) -> String {
        let lowered = name.lowercased()
        let cleaned = lowered.map { ch -> Character in
            (ch.isLetter && ch.isASCII) || ch.isNumber || ch == " " ? ch : " "
        }
        return String(cleaned)
            .components(separatedBy: .whitespaces)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private static func valueUnit(for ing: IngredientModel, mode: UnitMode) -> (Double?, String?) {
        switch mode {
        case .metric:
            if let v = ing.quantityValueMetric { return (v, ing.quantityUnitMetric) }
            return (ing.quantityValue, ing.quantityUnit)
        case .imperial:
            if let v = ing.quantityValueImperial { return (v, ing.quantityUnitImperial) }
            return (ing.quantityValue, ing.quantityUnit)
        case .original:
            return (ing.quantityValue, ing.quantityUnit)
        }
    }

    static func build(
        ingredients: [IngredientModel],
        scaleFactor: Double,
        unitMode: UnitMode
    ) -> [ShoppingLine] {
        // Preserve first-seen order of ingredient names.
        var order: [String] = []
        var groups: [String: [IngredientModel]] = [:]
        for ing in ingredients {
            let key = normalizeKey(ing.name)
            if key.isEmpty { continue }
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(ing)
        }

        return order.compactMap { key in
            guard let members = groups[key] else { return nil }

            // Sum numeric amounts bucketed by unit; carry non-numeric amounts as display text.
            var bucketOrder: [String] = []
            var buckets: [String: (sum: Double, unit: String?)] = [:]
            var nonNumeric: [String] = []

            for ing in members {
                let (value, unit) = valueUnit(for: ing, mode: unitMode)
                if let value = value {
                    let unitKey = (unit ?? "").lowercased().trimmingCharacters(in: .whitespaces)
                    if buckets[unitKey] == nil { bucketOrder.append(unitKey) }
                    let existing = buckets[unitKey]
                    buckets[unitKey] = ((existing?.sum ?? 0) + value, existing?.unit ?? unit)
                } else if let display = ing.quantityDisplay, !display.isEmpty, !nonNumeric.contains(display) {
                    nonNumeric.append(display)
                }
            }

            let parts = bucketOrder.compactMap { unitKey -> String? in
                guard let bucket = buckets[unitKey] else { return nil }
                return QuantityScaler.scale(
                    quantityValue: bucket.sum, quantityUnit: bucket.unit,
                    quantityDisplay: nil, scale: scaleFactor
                )
            } + nonNumeric

            let notes = members
                .compactMap { $0.shoppingNote?.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            var seenNotes = Set<String>()
            let note = notes.filter { seenNotes.insert($0).inserted }.joined(separator: "; ")

            return ShoppingLine(
                key: key,
                name: members.first?.name ?? key,
                quantity: parts.joined(separator: " + "),
                note: note.isEmpty ? nil : note
            )
        }
    }
}
