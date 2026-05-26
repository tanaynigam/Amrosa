import Foundation

enum UnitMode: String, CaseIterable {
    case original = "Original"
    case metric = "Metric"
    case imperial = "Imperial"
}

enum QuantityScaler {
    // MARK: - Scale ingredient display string

    static func scale(
        ingredient: IngredientModel,
        scaleFactor: Double,
        unitMode: UnitMode
    ) -> String {
        switch unitMode {
        case .metric where ingredient.quantityValueMetric != nil:
            let scaled = (ingredient.quantityValueMetric! * scaleFactor)
            return formatDisplay(
                value: scaled,
                unit: ingredient.quantityUnitMetric,
                baseDisplay: ingredient.quantityDisplayMetric
            )
        case .imperial where ingredient.quantityValueImperial != nil:
            let scaled = (ingredient.quantityValueImperial! * scaleFactor)
            return formatDisplay(
                value: scaled,
                unit: ingredient.quantityUnitImperial,
                baseDisplay: ingredient.quantityDisplayImperial
            )
        default:
            if let value = ingredient.quantityValue {
                let scaled = value * scaleFactor
                return formatDisplay(
                    value: scaled,
                    unit: ingredient.quantityUnit,
                    baseDisplay: ingredient.quantityDisplay
                )
            }
            return ingredient.quantityDisplay ?? ""
        }
    }

    // MARK: - Scale raw value/unit/display triple

    static func scale(
        quantityValue: Double?,
        quantityUnit: String?,
        quantityDisplay: String?,
        scale: Double
    ) -> String {
        guard let value = quantityValue else {
            return quantityDisplay ?? ""
        }
        let scaled = value * scale
        return formatDisplay(value: scaled, unit: quantityUnit, baseDisplay: quantityDisplay)
    }

    // MARK: - Private helpers

    private static func formatDisplay(value: Double, unit: String?, baseDisplay: String?) -> String {
        let formatted = formatValue(value)
        if let unit = unit, !unit.isEmpty {
            return "\(formatted) \(unit)"
        }
        return formatted
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
