import Foundation

enum UnitMode: String, CaseIterable {
    case original = "Original"
    case metric = "Metric"
    case imperial = "Imperial"
}

enum QuantityScaler {
    // MARK: - Scale ingredient display string (range-aware, F15)

    static func scale(
        ingredient: IngredientModel,
        scaleFactor: Double,
        unitMode: UnitMode
    ) -> String {
        switch unitMode {
        case .metric where ingredient.quantityValueMetric != nil:
            return rangeDisplay(min: ingredient.quantityValueMetric, max: ingredient.quantityValueMaxMetric,
                                unit: ingredient.quantityUnitMetric, baseDisplay: ingredient.quantityDisplayMetric,
                                scale: scaleFactor)
        case .imperial where ingredient.quantityValueImperial != nil:
            return rangeDisplay(min: ingredient.quantityValueImperial, max: ingredient.quantityValueMaxImperial,
                                unit: ingredient.quantityUnitImperial, baseDisplay: ingredient.quantityDisplayImperial,
                                scale: scaleFactor)
        default:
            return rangeDisplay(min: ingredient.quantityValue, max: ingredient.quantityValueMax,
                                unit: ingredient.quantityUnit, baseDisplay: ingredient.quantityDisplay,
                                scale: scaleFactor)
        }
    }

    // MARK: - Scale SharedIngredient (Firestore-only model)

    static func scaleShared(
        ingredient: SharedIngredient,
        scaleFactor: Double,
        unitMode: UnitMode
    ) -> String {
        switch unitMode {
        case .metric where ingredient.quantityValueMetric != nil:
            return rangeDisplay(min: ingredient.quantityValueMetric, max: ingredient.quantityValueMaxMetric,
                                unit: ingredient.quantityUnitMetric, baseDisplay: ingredient.quantityDisplayMetric,
                                scale: scaleFactor)
        case .imperial where ingredient.quantityValueImperial != nil:
            return rangeDisplay(min: ingredient.quantityValueImperial, max: ingredient.quantityValueMaxImperial,
                                unit: ingredient.quantityUnitImperial, baseDisplay: ingredient.quantityDisplayImperial,
                                scale: scaleFactor)
        default:
            return rangeDisplay(min: ingredient.quantityValue, max: ingredient.quantityValueMax,
                                unit: ingredient.quantityUnit, baseDisplay: ingredient.quantityDisplay,
                                scale: scaleFactor)
        }
    }

    // MARK: - Scale raw value/unit/display triple

    static func scale(
        quantityValue: Double?,
        quantityUnit: String?,
        quantityDisplay: String?,
        scale: Double
    ) -> String {
        rangeDisplay(min: quantityValue, max: nil, unit: quantityUnit, baseDisplay: quantityDisplay, scale: scale)
    }

    /// Range-aware (F15): renders "min–max unit" scaling BOTH ends when `max > min`,
    /// otherwise a single scaled value. Falls back to `quantityDisplay` when `quantityValue` is nil.
    static func scale(
        quantityValue: Double?,
        quantityValueMax: Double?,
        quantityUnit: String?,
        quantityDisplay: String?,
        scale: Double
    ) -> String {
        rangeDisplay(min: quantityValue, max: quantityValueMax, unit: quantityUnit, baseDisplay: quantityDisplay, scale: scale)
    }

    // MARK: - Private helpers

    private static func rangeDisplay(min: Double?, max: Double?, unit: String?, baseDisplay: String?, scale: Double) -> String {
        guard let min = min else { return baseDisplay ?? "" }
        let minStr = formatValue(min * scale)
        let number: String
        if let max = max, max > min {
            number = "\(minStr)–\(formatValue(max * scale))"
        } else {
            number = minStr
        }
        if let unit = unit, !unit.isEmpty { return "\(number) \(unit)" }
        return number
    }

    private static func formatValue(_ value: Double) -> String {
        if value == 0 { return "0" }

        // Try to express as a simple fraction for common cooking measurements
        let fractions: [(Double, String)] = [
            (1.0/8, "⅛"), (1.0/4, "¼"), (1.0/3, "⅓"), (3.0/8, "⅜"),
            (1.0/2, "½"), (5.0/8, "⅝"), (2.0/3, "⅔"), (3.0/4, "¾"), (7.0/8, "⅞")
        ]

        let whole = Int(value)
        let remainder = value - Double(whole)

        if remainder < 0.01 {
            return "\(whole)"
        }

        for (fraction, symbol) in fractions {
            if abs(remainder - fraction) < 0.04 {
                return whole > 0 ? "\(whole)\(symbol)" : symbol
            }
        }

        // Fall back to decimal, trimming trailing zeros
        let str = String(format: "%.2f", value)
        return str.replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression)
    }
}
