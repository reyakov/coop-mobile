import SwiftUI
import Shared

@MainActor
@Observable
final class ChatViewModel {
    let roomId: Int64

    var messages: [Nostr_sdk_kmpUnsignedEvent] = []
    var room: Room?
    var roomUi: RoomUiState?
    var loading = true
    var replyingTo: Nostr_sdk_kmpUnsignedEvent?
    var requireScreening: Bool

    private var subscriptions: [FlowSubscription] = []

    init(roomId: Int64, screening: Bool) {
        self.roomId = roomId
        self.requireScreening = screening
    }

    func start(appState: AppState) {
        let bootstrap = appState.bootstrap

        room = bootstrap.getChatRoom(id: roomId)
        if let room {
            subscriptions.append(bootstrap.watchRoomUi(
                room: room,
                currentUser: bootstrap.currentPublicKey()
            ) { [weak self] state in
                Task { @MainActor in self?.roomUi = state }
            })
        }

        subscriptions.append(bootstrap.watchNewEvents { [weak self] event in
            Task { @MainActor in
                guard let self, event.roomId() == self.roomId else { return }
                if !self.messages.contains(where: { $0.id()?.toHex() == event.id()?.toHex() }) {
                    self.messages.append(event)
                    self.messages.sort { $0.createdAt().asSecs() < $1.createdAt().asSecs() }
                }
                appState.bootstrap.markRoomRead(id: self.roomId)
            }
        })

        Task {
            let loaded = (try? await bootstrap.loadRoomMessages(roomId: roomId)) ?? []
            messages = loaded.sorted { $0.createdAt().asSecs() < $1.createdAt().asSecs() }
            loading = false
            bootstrap.markRoomRead(id: roomId)
            bootstrap.connectRoom(id: roomId)
        }
    }

    func stop() {
        subscriptions.forEach { $0.cancel() }
        subscriptions = []
    }

    func send(_ text: String, appState: AppState) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        if let replyTo = replyingTo, let replyId = replyTo.id() {
            appState.bootstrap.sendReplyMessage(roomId: roomId, text: trimmed, replyTo: replyId)
        } else {
            appState.bootstrap.sendTextMessage(roomId: roomId, text: trimmed)
        }
        replyingTo = nil
        requireScreening = false
    }

    func sendImage(_ data: Data, contentType: String, appState: AppState) {
        appState.bootstrap.sendImageMessage(
            roomId: roomId,
            file: data.toKotlinByteArray(),
            contentType: contentType
        )
        requireScreening = false
    }
}
