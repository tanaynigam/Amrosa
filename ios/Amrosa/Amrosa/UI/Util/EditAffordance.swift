import SwiftUI

/// Edit-mode affordance (F16): when `active`, the element gently **jiggles** (a small continuous
/// wobble) and gains a faint rounded **outline**, and a tap invokes `onTap` to open its edit sheet.
/// When inactive this is a complete no-op, so the read-only view is unchanged.
///
/// `phase` staggers the wobble between items (pass the item index) so the list looks organic
/// rather than every row rocking in lockstep.
private struct EditableModifier: ViewModifier {
    let active: Bool
    let phase: Int
    let onTap: () -> Void
    @State private var wobble = false

    func body(content: Content) -> some View {
        if active {
            content
                .rotationEffect(.degrees(wobble ? 0.6 : -0.6))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(Color.secondary.opacity(0.5), lineWidth: 1)
                )
                .contentShape(Rectangle())
                .onTapGesture(perform: onTap)
                .onAppear {
                    withAnimation(
                        .easeInOut(duration: 0.16)
                            .repeatForever(autoreverses: true)
                            .delay(Double(phase % 6) * 0.045)
                    ) { wobble = true }
                }
        } else {
            content
        }
    }
}

extension View {
    func editable(active: Bool, phase: Int = 0, onTap: @escaping () -> Void) -> some View {
        modifier(EditableModifier(active: active, phase: phase, onTap: onTap))
    }

    /// F19 drag-and-drop reorder (edit mode only): the row is a drag source carrying `id` and a drop
    /// target — dropping another row's id here calls `onDrop(draggedId)`. A no-op when inactive, so
    /// the read-only view is unaffected. Long-press to drag; a quick tap still opens the edit sheet.
    @ViewBuilder
    func dragReorder(active: Bool, id: String, onDrop: @escaping (String) -> Void) -> some View {
        if active {
            self.draggable(id)
                .dropDestination(for: String.self) { items, _ in
                    if let dragged = items.first, dragged != id { onDrop(dragged) }
                    return true
                }
        } else {
            self
        }
    }
}
