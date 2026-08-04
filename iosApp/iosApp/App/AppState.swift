import Foundation
import Shared

@MainActor
@Observable
final class AppState {
    let bootstrap: IosBootstrap

    private var subscriptions: [FlowSubscription] = []

    var path: [AppRoute] = []
    var signerRequired: Bool?
    var isSyncing = false
    var partialProcessed = false
    var chatRooms: [Room] = []
    var accountState: AccountState?
    var settings: Settings?
    var currentUserProfile: Profile?
    var isUpdatingProfile = false
    var errorMessage: String?

    let networkMonitor = NetworkMonitor()

    init() {
        bootstrap = IosBootstrap.companion.create(storage: IosAppStorage())
    }

    func start() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dbDir = docs.appendingPathComponent("nostr", isDirectory: true)
        try? FileManager.default.createDirectory(at: dbDir, withIntermediateDirectories: true)

        bootstrap.start(dbPath: dbDir.path) { [weak self] event in
            self?.handleNewMessage(event)
        }

        subscriptions.append(bootstrap.watchAccountState { [weak self] state in
            self?.accountState = state
            self?.signerRequired = state.signerRequired?.boolValue
        })
        subscriptions.append(bootstrap.watchChatRooms { [weak self] rooms in
            self?.chatRooms = rooms
        })
        subscriptions.append(bootstrap.watchIsSyncing { [weak self] value in
            self?.isSyncing = value.boolValue
        })
        subscriptions.append(bootstrap.watchPartialProcessed { [weak self] value in
            self?.partialProcessed = value.boolValue
        })
        subscriptions.append(bootstrap.watchSettings { [weak self] settings in
            self?.settings = settings
        })
        subscriptions.append(bootstrap.watchCurrentUserProfile { [weak self] profile in
            self?.currentUserProfile = profile
        })
        subscriptions.append(bootstrap.watchIsUpdatingProfile { [weak self] value in
            self?.isUpdatingProfile = value.boolValue
        })
        subscriptions.append(bootstrap.watchErrors { [weak self] message in
            self?.errorMessage = message
        })
    }

    func handle(_ url: URL) {
        guard url.scheme == "coop" else { return }
        switch url.host {
        case "chat":
            if let id = Int64(url.pathComponents.dropFirst().first ?? "") {
                path.append(.chat(id: id, screening: false))
            }
        case "profile":
            let pubkey = url.pathComponents.dropFirst().first ?? ""
            if !pubkey.isEmpty {
                path.append(.profile(pubkey: pubkey))
            }
        default:
            break
        }
    }

    func logout() {
        bootstrap.logout()
        bootstrap.resetState()
        path = []
    }

    private func handleNewMessage(_ event: Nostr_sdk_kmpUnsignedEvent) {
        NotificationService.shared.notifyNewMessage(roomId: event.roomId(), content: event.content())
    }
}
