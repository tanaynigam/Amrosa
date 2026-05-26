import SwiftUI
import SwiftData
import Firebase
import GoogleSignIn

@main
struct AmrosaApp: App {
    @State private var appContainer: AppContainer

    init() {
        FirebaseApp.configure()
        _appContainer = State(initialValue: AppContainer())
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(appContainer)
                .modelContainer(appContainer.modelContainer)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
                .task {
                    await appContainer.onLaunch()
                }
        }
    }
}
