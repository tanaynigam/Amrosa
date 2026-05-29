import SwiftUI
import SwiftData
import Firebase
import GoogleSignIn
import UserNotifications

@main
struct AmrosaApp: App {
    // Wires in our AppDelegate for APNs + FCM delegate callbacks
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

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
                .onReceive(NotificationCenter.default.publisher(for: .fcmTokenRefreshed)) { note in
                    guard let token = note.object as? String else { return }
                    Task { await appContainer.socialRepository.storeFcmToken(token) }
                }
        }
    }
}
