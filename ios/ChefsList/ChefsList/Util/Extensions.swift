import Foundation
import SwiftUI

extension String {
    var isValidURL: Bool {
        guard let url = URL(string: self) else { return false }
        return url.scheme == "http" || url.scheme == "https"
    }

    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

extension Date {
    func relativeString() -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: self, relativeTo: Date())
    }
}

extension Int {
    var timeDisplayString: String {
        if self < 60 { return "\(self) min" }
        let hours = self / 60
        let minutes = self % 60
        if minutes == 0 { return "\(hours) hr" }
        return "\(hours) hr \(minutes) min"
    }
}

extension Array where Element: Hashable {
    /// Returns the array with duplicates removed, preserving original order.
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}

extension View {
    func cardStyle() -> some View {
        self
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .shadow(color: .black.opacity(0.06), radius: 4, x: 0, y: 2)
    }
}

extension Int {
    /// Compact like/save count: 1.2k / 3M (mirrors Android `compactCount`).
    var compactCount: String {
        if self < 1_000 { return "\(self)" }
        if self < 1_000_000 {
            let v = Double(self) / 1_000
            return v.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(v))k" : String(format: "%.1fk", v)
        }
        let v = Double(self) / 1_000_000
        return v.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(v))M" : String(format: "%.1fM", v)
    }
}
