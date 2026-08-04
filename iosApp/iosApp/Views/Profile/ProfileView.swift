import SwiftUI
import Shared

struct ProfileView: View {
    @Environment(AppState.self) private var appState

    let pubkey: String

    @State private var profile: Profile?
    @State private var subscription: FlowSubscription?
    @State private var parsedKey: Nostr_sdk_kmpPublicKey?

    private var record: Nostr_sdk_kmpMetadataRecord? {
        profile?.metadata.asRecord()
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    AvatarView(
                        name: profile?.name ?? "?",
                        picture: profile?.picture,
                        size: 120
                    )
                    Text(profile?.name ?? "No name")
                        .font(.title2.bold())
                    Text(record?.nip05 ?? parsedKey?.short() ?? "")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical)
            }
            .listRowBackground(Color.clear)

            Section("Details") {
                LabeledContent("Username", value: record?.name ?? "None")
                LabeledContent("Website", value: record?.website ?? "None")
                LabeledContent("Lightning Address", value: record?.lud16 ?? "None")
            }

            Section {
                Button {
                    openChat()
                } label: {
                    Label("Message", systemImage: "message")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

                if let npub = try? parsedKey?.toBech32() {
                    ShareLink(item: npub) {
                        Label("Share", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .listRowBackground(Color.clear)
        }
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            parsedKey = appState.bootstrap.parsePublicKey(input: pubkey)
            guard let parsedKey else { return }
            subscription = appState.bootstrap.watchProfile(pubkey: parsedKey) { value in
                Task { @MainActor in profile = value }
            }
        }
        .onDisappear {
            subscription?.cancel()
        }
    }

    private func openChat() {
        guard let parsedKey else { return }
        do {
            let roomId = try appState.bootstrap.createChatRoom(recipients: [parsedKey])
            appState.path.append(.chat(id: roomId, screening: false))
        } catch {
            appState.errorMessage = error.localizedDescription
        }
    }
}
