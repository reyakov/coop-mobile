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
    @State private var authorProfiles: [String: Profile] = [:]

    private var showScreener: Bool {
        (viewModel?.requireScreening ?? false) && appState.settings?.screening == true
    }

    private var isGroup: Bool {
        viewModel?.room?.isGroup() == true
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
                                size: 30
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
            LazyVStack(spacing: 2) {
                ForEach(groupedMessages, id: \.0) { group, messages in
                    Text(headerTitle(group: group, first: messages.first))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 14)

                    ForEach(Array(messages.enumerated()), id: \.element.stableId) { index, event in
                        messageCell(event: event, at: index, in: messages)
                    }
                }

                if let last = viewModel?.messages.last, isMine(last) {
                    Text("Delivered")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(.top, 2)
                }
            }
            .padding(.horizontal, 12)
        }
        .defaultScrollAnchor(.bottom)
        .scrollDismissesKeyboard(.interactively)
        .overlay {
            if viewModel?.loading == true {
                ProgressView()
            }
        }
    }

    @ViewBuilder
    private func messageCell(
        event: Nostr_sdk_kmpUnsignedEvent,
        at index: Int,
        in messages: [Nostr_sdk_kmpUnsignedEvent]
    ) -> some View {
        let mine = isMine(event)
        let authorHex = event.author().toHex()
        let nextIsSameAuthor = index + 1 < messages.count &&
            messages[index + 1].author().toHex() == authorHex
        let prevIsSameAuthor = index > 0 &&
            messages[index - 1].author().toHex() == authorHex
        let replyId = event.tags().eventIds().first
        let replied = replyId.flatMap { id in
            viewModel?.messages.first { $0.id()?.toHex() == id.toHex() }
        }

        MessageBubble(
            event: event,
            isMine: mine,
            showImages: showImages,
            isFirstOfRun: !prevIsSameAuthor,
            isLastOfRun: !nextIsSameAuthor,
            showAuthorName: isGroup && !mine && !prevIsSameAuthor,
            showAuthorAvatar: isGroup && !mine,
            authorName: authorName(for: event),
            authorPicture: authorProfiles[authorHex]?.picture,
            repliedMessage: replied,
            repliedAuthorName: replied.flatMap { authorName(for: $0) },
            onReply: { viewModel?.replyingTo = event }
        )
        .padding(.bottom, nextIsSameAuthor ? 0 : 8)
    }

    private var inputBar: some View {
        VStack(spacing: 0) {
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
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(.bar)
            }

            HStack(alignment: .bottom, spacing: 10) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(.secondary)
                        .frame(width: 34, height: 34)
                        .background(Color(.secondarySystemFill), in: Circle())
                }

                TextField("Message", text: $input, axis: .vertical)
                    .lineLimit(1...6)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background {
                        Capsule()
                            .strokeBorder(Color(.systemGray4), lineWidth: 1)
                    }

                if !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        viewModel?.send(input, appState: appState)
                        input = ""
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(Color(.systemBlue))
                    }
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.bar)
            .animation(.snappy(duration: 0.2), value: input.isEmpty)
        }
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

    private func headerTitle(group: String, first: Nostr_sdk_kmpUnsignedEvent?) -> String {
        guard let first else { return group }
        return "\(group) \(first.createdAt().formatAsTime())"
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

    private func isMine(_ event: Nostr_sdk_kmpUnsignedEvent) -> Bool {
        event.author().toHex() == appState.bootstrap.currentPublicKey()?.toHex()
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
        if let profile = authorProfiles[hex] {
            return profile.name
        }
        loadAuthorProfile(pubkey: event.author(), hex: hex)
        return event.author().short()
    }

    private func loadAuthorProfile(pubkey: Nostr_sdk_kmpPublicKey, hex: String) {
        guard authorProfiles[hex] == nil else { return }
        let sub = appState.bootstrap.watchProfile(pubkey: pubkey) { profile in
            Task { @MainActor in
                if let profile {
                    authorProfiles[hex] = profile
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
