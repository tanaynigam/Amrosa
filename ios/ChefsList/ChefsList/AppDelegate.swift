import UIKit
import FirebaseMessaging
import UserNotifications

// MARK: - App-wide notification names for push events

extension Notification.Name {
    /// Posted when FCM delivers a new registration token.
    /// `object` is the token String.
    static let fcmTokenRefreshed = Notification.Name("ChefsListFCMTokenRefreshed")

    /// Posted when the user taps a push notification.
    /// `userInfo` contains "type" and "shareId" strings.
    static let pushNotificationTapped = Notification.Name("ChefsListPushNotificationTapped")
}

// MARK: - AppDelegate

class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Set notification delegates (Firebase swizzling is disabled in Info.plist)
        UNUserNotificationCenter.current().delegate = self
        Messaging.messaging().delegate = self
        return true
    }

    // Called by iOS after the user grants permission and the device registers.
    // We must forward the raw APNs token to FirebaseMessaging — this is how
    // FCM maps APNs tokens to FCM registration tokens.
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        print("APNs registration failed: \(error.localizedDescription)")
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension AppDelegate: UNUserNotificationCenterDelegate {

    /// Show banner + sound even when the app is in the foreground.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        return [.banner, .sound, .badge]
    }

    /// User tapped the notification — post an internal event so ContentView can navigate.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        let type    = info["type"]    as? String ?? ""
        let shareId = info["shareId"] as? String ?? ""
        NotificationCenter.default.post(
            name: .pushNotificationTapped,
            object: nil,
            userInfo: ["type": type, "shareId": shareId]
        )
    }
}

// MARK: - MessagingDelegate

extension AppDelegate: MessagingDelegate {

    /// FCM delivers a new registration token (on first launch or token refresh).
    /// Broadcast it so AppContainer can store it in Firestore.
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken else { return }
        NotificationCenter.default.post(name: .fcmTokenRefreshed, object: token)
    }
}
