import SwiftUI
import Shared

struct RoomRow: View {
    @Environment(AppState.self) private var appState
    let room: Room
    @State private var ui: RoomUiState?
    @State private var subscription: FlowSubscription?

    private var unread: Bool { room.unreadCount > 0 }

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(unread ? Color.accentColor : .clear)
                .frame(width: 10, height: 10)

            AvatarView(name: ui?.name ?? "?", picture: ui?.picture, size: 48)

            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(ui?.name ?? "Loading...")
                        .font(.body.weight(unread ? .semibold : .regular))
                        .lineLimit(1)

                    Spacer()

                    Text(room.createdAt.ago())
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color(.systemGray3))
                }

                Text(room.lastMessage ?? "")
                    .font(.subheadline.weight(unread ? .semibold : .regular))
                    .foregroundStyle(unread ? .primary : .secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
        .task {
            subscription = appState.bootstrap.watchRoomUi(
                room: room,
                currentUser: appState.bootstrap.currentPublicKey()
            ) { state in
                Task { @MainActor in ui = state }
            }
        }
        .onDisappear {
            subscription?.cancel()
            subscription = nil
        }
    }
}
