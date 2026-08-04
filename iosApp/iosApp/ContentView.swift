import SwiftUI
import Shared

struct ContentView: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        Group {
            switch appState.signerRequired {
            case .none:
                SplashView()
            case .some(true):
                OnboardingView()
            case .some(false):
                NavigationStack(path: Bindable(appState).path) {
                    HomeView()
                        .navigationDestination(for: AppRoute.self) { route in
                            destination(for: route)
                        }
                }
            }
        }
        .alert("Error", isPresented: Binding(
            get: { appState.errorMessage != nil },
            set: { if !$0 { appState.errorMessage = nil } }
        )) {
            Button("OK") { appState.errorMessage = nil }
        } message: {
            Text(appState.errorMessage ?? "")
        }
        .preferredColorScheme(colorScheme)
    }

    private var colorScheme: ColorScheme? {
        switch appState.settings?.theme {
        case Theme.light:
            return .light
        case Theme.dark:
            return .dark
        default:
            return nil
        }
    }

    @ViewBuilder
    private func destination(for route: AppRoute) -> some View {
        switch route {
        case .home:
            HomeView()
        case .requestList:
            RequestListView()
        case .contactList:
            ContactListView()
        case .updateProfile:
            UpdateProfileView()
        case .newChat:
            NewChatView()
        case .myQr:
            MyQrView()
        case .relay:
            RelayView()
        case .settings:
            SettingsView()
        case .chat(let id, let screening):
            ChatView(roomId: id, screening: screening)
        case .profile(let pubkey):
            ProfileView(pubkey: pubkey)
        }
    }
}

struct SplashView: View {
    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
        }
    }
}
