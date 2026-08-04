import SwiftUI
import Shared

struct RoomRow: View {
    @Environment(AppState.self) private var appState
    let room: Room
    @State private var ui: RoomUiState?
    @State private var subscription: FlowSubscription?

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: ui?.name ?? "?", picture: ui?.picture)

            VStack(alignment: .leading, spacing: 2) {
                Text(ui?.name ?? "Loading...")
                    .font(.headline)
                    .lineLimit(1)
                Text(room.lastMessage ?? "")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 4) {
                Text(room.createdAt.ago())
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if room.unreadCount > 0 {
                    Text("\(room.unreadCount)")
                        .font(.caption2.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.accentColor, in: Capsule())
                }
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
