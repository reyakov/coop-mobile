import SwiftUI
import Shared

struct HomeView: View {
    @Environment(AppState.self) private var appState
    @State private var showProfileSheet = false
    @State private var showScanner = false
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
                    HStack(spacing: 12) {
                        Image(systemName: "tray.full")
                            .font(.title2)
                            .foregroundStyle(.tint)
                            .frame(width: 44)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("New Requests")
                                .font(.headline)
                            Text("\(requestRooms.count) request\(requestRooms.count == 1 ? "" : "s")")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        if requestUnread > 0 {
                            Text("\(requestUnread)")
                                .font(.caption2.bold())
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.accentColor, in: Capsule())
                        }
                    }
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
                    "No chats yet",
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text("Start a new chat to begin messaging")
                )
            }
        }
        .navigationTitle("Coop")
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 8) {
                    Text("Coop").font(.headline)
                    if appState.isSyncing {
                        ProgressView().controlSize(.small)
                    }
                }
            }
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    showScanner = true
                } label: {
                    Image(systemName: "qrcode.viewfinder")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showProfileSheet = true
                } label: {
                    AvatarView(
                        name: appState.currentUserProfile?.name ?? "?",
                        picture: appState.currentUserProfile?.picture,
                        size: 32
                    )
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                appState.path.append(.newChat)
            } label: {
                Label("New Chat", systemImage: "square.and.pencil")
                    .font(.headline)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .padding(.bottom, 8)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.trailing)
        }
        .sheet(isPresented: $showProfileSheet) {
            ProfileSheetView()
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showScanner) {
            ScanView { result in
                showScanner = false
                if let pubkey = appState.bootstrap.parsePublicKey(input: result) {
                    do {
                        let roomId = try appState.bootstrap.createChatRoom(recipients: [pubkey])
                        appState.path.append(.chat(id: roomId, screening: false))
                    } catch {
                        appState.errorMessage = error.localizedDescription
                    }
                } else {
                    appState.errorMessage = "Invalid public key"
                }
            }
        }
        .sheet(isPresented: $showRelayWarning) {
            RelayWarningSheet()
        }
        .onChange(of: appState.accountState?.isRelayListEmpty) { _, isEmpty in
            showRelayWarning = isEmpty == true
        }
    }
}
