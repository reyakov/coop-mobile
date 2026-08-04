import PhotosUI
import SwiftUI
import Shared

struct ChatView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    let roomId: Int64
    let screening: Bool

    @State private var viewModel: ChatViewModel?
    @State private var input = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var authorNames: [String: String] = [:]

    private var showScreener: Bool {
        (viewModel?.requireScreening ?? false) && appState.settings?.screening == true
    }

    var body: some View {
        VStack(spacing: 0) {
            messageList

            if showScreener, let room = viewModel?.room {
                ScreenerCard(
                    room: room,
                    onAccept: { viewModel?.requireScreening = false },
                    onReject: { dismiss() }
                )
            } else {
                inputBar
            }
        }
        .navigationTitle(viewModel?.roomUi?.name ?? "Chat")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                if let room = viewModel?.room, let firstMember = otherMember(of: room) {
                    Button {
                        appState.path.append(.profile(pubkey: firstMember.toHex()))
                    } label: {
                        VStack(spacing: 2) {
                            AvatarView(
                                name: viewModel?.roomUi?.name ?? "?",
                                picture: viewModel?.roomUi?.picture,
                                size: 28
                            )
                            Text(viewModel?.roomUi?.name ?? "Chat")
                                .font(.caption)
                                .foregroundStyle(.primary)
                        }
                    }
                } else {
                    Text(viewModel?.roomUi?.name ?? "Chat").font(.headline)
                }
            }
        }
        .task {
            let vm = ChatViewModel(roomId: roomId, screening: screening)
            viewModel = vm
            vm.start(appState: appState)
        }
        .onDisappear {
            viewModel?.stop()
        }
        .task(id: photoItem) {
            guard let photoItem else { return }
            guard let data = try? await photoItem.loadTransferable(type: Data.self) else { return }
            let contentType = photoItem.supportedContentTypes.first?.preferredMIMEType ?? "image/jpeg"
            viewModel?.sendImage(data, contentType: contentType, appState: appState)
            self.photoItem = nil
        }
    }

    private var messageList: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                ForEach(groupedMessages, id: \.0) { group, messages in
                    Text(group)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 8)

                    ForEach(messages, id: \.stableId) { event in
                        let isMine = event.author().toHex() == appState.bootstrap.currentPublicKey()?.toHex()
                        let replyId = event.tags().eventIds().first
                        let replied = replyId.flatMap { id in
                            viewModel?.messages.first { $0.id()?.toHex() == id.toHex() }
                        }
                        MessageBubble(
                            event: event,
                            isMine: isMine,
                            showImages: showImages,
                            repliedMessage: replied,
                            repliedAuthorName: replied.flatMap { authorName(for: $0) },
                            onReply: { viewModel?.replyingTo = event }
                        )
                    }
                }
            }
            .padding(.horizontal)
        }
        .defaultScrollAnchor(.bottom)
        .overlay {
            if viewModel?.loading == true {
                ProgressView()
            }
        }
    }

    private var inputBar: some View {
        VStack(spacing: 8) {
            if let replyingTo = viewModel?.replyingTo {
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.accentColor)
                        .frame(width: 3)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Replying to \(authorName(for: replyingTo))")
                            .font(.caption.bold())
                        Text(replyingTo.content())
                            .font(.caption)
                            .lineLimit(1)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        viewModel?.replyingTo = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal)
                .padding(.top, 8)
            }

            HStack(spacing: 12) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Image(systemName: "plus.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.tint)
                }

                TextField("Message", text: $input, axis: .vertical)
                    .lineLimit(1...5)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground), in: Capsule())

                Button {
                    viewModel?.send(input, appState: appState)
                    input = ""
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                        .foregroundStyle(input.isEmpty ? Color(.systemGray3) : Color.accentColor)
                }
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .background(.bar)
    }

    private var groupedMessages: [(String, [Nostr_sdk_kmpUnsignedEvent])] {
        let messages = viewModel?.messages ?? []
        var groups: [(String, [Nostr_sdk_kmpUnsignedEvent])] = []
        var currentKey = ""

        for message in messages {
            let key = message.createdAt().formatAsGroup()
            if key != currentKey {
                groups.append((key, [message]))
                currentKey = key
            } else {
                groups[groups.count - 1].1.append(message)
            }
        }
        return groups
    }

    private var showImages: Bool {
        guard let media = appState.settings?.media else { return true }
        switch media {
        case MediaConfig.disabled:
            return false
        case MediaConfig.disabledformobiledata:
            return !appState.networkMonitor.isMobileData
        default:
            return true
        }
    }

    private func otherMember(of room: Room) -> Nostr_sdk_kmpPublicKey? {
        let selfHex = appState.bootstrap.currentPublicKey()?.toHex()
        return room.members.first { $0.toHex() != selfHex } ?? room.members.first
    }

    private func authorName(for event: Nostr_sdk_kmpUnsignedEvent) -> String {
        let hex = event.author().toHex()
        if hex == appState.bootstrap.currentPublicKey()?.toHex() {
            return appState.currentUserProfile?.name ?? "You"
        }
        if let cached = authorNames[hex] {
            return cached
        }
        let short = event.author().short()
        loadAuthorName(pubkey: event.author(), hex: hex)
        return short
    }

    private func loadAuthorName(pubkey: Nostr_sdk_kmpPublicKey, hex: String) {
        guard authorNames[hex] == nil else { return }
        authorNames[hex] = pubkey.short()
        let sub = appState.bootstrap.watchProfile(pubkey: pubkey) { profile in
            Task { @MainActor in
                if let profile {
                    authorNames[hex] = profile.name
                }
            }
        }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(30))
            sub.cancel()
        }
    }
}

private extension Nostr_sdk_kmpUnsignedEvent {
    var stableId: String {
        id()?.toHex() ?? "\(createdAt().asSecs())-\(content().hashValue)"
    }
}
