import SwiftUI

struct DiscoverView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "sparkles")
                .font(.system(size: 56))
                .foregroundStyle(Color.accentColor)
            Text("Discover")
                .font(.title2)
                .fontWeight(.semibold)
            Text("Personalised recipe recommendations are coming soon.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle("Discover")
    }
}
