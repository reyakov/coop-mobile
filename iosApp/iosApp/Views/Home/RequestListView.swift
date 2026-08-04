import SwiftUI
import Shared

struct RequestListView: View {
    @Environment(AppState.self) private var appState

    private var requestRooms: [Room] {
        appState.chatRooms.filter { $0.kind == RoomKind.request }
    }

    var body: some View {
        List {
            ForEach(requestRooms, id: \.id) { room in
                Button {
                    appState.path.append(.chat(id: room.id, screening: true))
                } label: {
                    RoomRow(room: room)
                }
                .tint(.primary)
            }
        }
        .refreshable {
            appState.bootstrap.refreshChatRooms()
        }
        .overlay {
            if requestRooms.isEmpty {
                ContentUnavailableView(
                    "No requests",
                    systemImage: "tray",
                    description: Text("New message requests will appear here")
                )
            }
        }
        .navigationTitle("Requests")
    }
}
