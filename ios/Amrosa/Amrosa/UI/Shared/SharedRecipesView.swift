import SwiftUI

struct SharedRecipesView: View {
    var body: some View {
        ContentUnavailableView(
            "Coming Soon",
            systemImage: "globe",
            description: Text("Community shared recipes will appear here.")
        )
        .navigationTitle("Shared")
    }
}
