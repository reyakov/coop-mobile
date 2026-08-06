import SwiftUI

@main
struct iOSApp: App {
    @State private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(appState)
                .onAppear {
                    appState.start()
                    NotificationService.shared.requestPermissionIfNeeded()
                    NotificationService.shared.onOpenChat = { roomId in
                        appState.path.append(.chat(id: roomId, screening: false))
                    }
                }
                .onOpenURL { url in
                    appState.handle(url)
                }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        appState.resume()
                    case .background:
                        appState.pause()
                    default:
                        break
                    }
                }
        }
    }
}
