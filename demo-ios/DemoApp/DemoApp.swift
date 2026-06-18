import SwiftUI
import DeltaListCore
import UserNotifications

@main
struct DemoApp: App {
    init() {
        // App-wired routing: DeltaListCore never claims the delegate, so install the router here
        // (or forward from your own delegate). Required for notification taps/actions to route back.
        UNUserNotificationCenter.current().delegate = DeltaNotificationRouter.shared
        DeltaNotificationRouter.requestAuthorization()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
