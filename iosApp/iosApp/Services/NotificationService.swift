import Foundation
import UserNotifications

final class NotificationService: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationService()

    private let center = UNUserNotificationCenter.current()

    var onOpenChat: ((Int64) -> Void)?

    override init() {
        super.init()
        center.delegate = self
    }

    func requestPermissionIfNeeded() {
        center.getNotificationSettings { settings in
            if settings.authorizationStatus == .notDetermined {
                self.center.requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
            }
        }
    }

    func notifyNewMessage(roomId: Int64, content: String) {
        let notificationContent = UNMutableNotificationContent()
        notificationContent.title = "You received a new message"
        notificationContent.body = content
        notificationContent.sound = .default
        notificationContent.userInfo = ["roomId": roomId]

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: notificationContent,
            trigger: nil
        )
        center.add(request)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        []
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        if let roomId = response.notification.request.content.userInfo["roomId"] as? Int64 {
            await MainActor.run {
                onOpenChat?(roomId)
            }
        }
    }
}
