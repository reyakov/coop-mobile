import SwiftUI
import Shared

struct HomeView: View {
    @Environment(AppState.self) private var appState
    @State private var showProfileSheet = false
    @State private var showRelayWarning = false

    private var ongoingRooms: [Room] {
        appState.chatRooms.filter { $0.kind == RoomKind.ongoing }
    }

    private var requestRooms: [Room] {
        appState.chatRooms.filter { $0.kind == RoomKind.request }
    }

    private var requestUnread: Int {
        requestRooms.reduce(0) { $0 + Int($1.unreadCount) }
    }

    var body: some View {
        List {
            if !requestRooms.isEmpty {
                Button {
                    appState.path.append(.requestList)
                } label: {
                    HStack(spacing: 10) {
                        Circle()
                            .fill(requestUnread > 0 ? Color.accentColor : .clear)
                            .frame(width: 10, height: 10)

                        Image(systemName: "tray.full")
                            .font(.title2)
                            .foregroundStyle(.secondary)
                            .frame(width: 48, height: 48)
                            .background(Color(.secondarySystemFill), in: Circle())

                        VStack(alignment: .leading, spacing: 3) {
                            Text("Message Requests")
                                .font(.body.weight(requestUnread > 0 ? .semibold : .regular))
                            Text("\(requestRooms.count)")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color(.systemGray3))
                    }
                    .padding(.vertical, 4)
                }
                .tint(.primary)
            }

            ForEach(ongoingRooms, id: \.id) { room in
                Button {
                    appState.path.append(.chat(id: room.id, screening: false))
                } label: {
                    RoomRow(room: room)
                }
                .tint(.primary)
            }
        }
        .listStyle(.plain)
        .refreshable {
            appState.bootstrap.refreshChatRooms()
        }
        .overlay {
            if !appState.partialProcessed {
                ProgressView()
            } else if appState.chatRooms.isEmpty {
                ContentUnavailableView(
                    "No Messages",
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text("Start a new conversation")
                )
            }
        }
        .navigationTitle("Coop")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    showProfileSheet = true
                } label: {
                    AvatarView(
                        name: appState.currentUserProfile?.name ?? "?",
                        picture: appState.currentUserProfile?.picture,
                        size: 30
                    )
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 16) {
                    if appState.isSyncing {
                        ProgressView().controlSize(.small)
                    }
                    Button {
                        appState.path.append(.newChat)
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                }
            }
        }
        .sheet(isPresented: $showProfileSheet) {
            ProfileSheetView()
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showRelayWarning) {
            RelayWarningSheet()
        }
        .onChange(of: appState.accountState?.isRelayListEmpty) { _, isEmpty in
            showRelayWarning = isEmpty == true
        }
    }
}
