import SwiftUI
import Shared

struct ScreenerCard: View {
    @Environment(AppState.self) private var appState

    let room: Room
    let onAccept: () -> Void
    let onReject: () -> Void

    @State private var profile: Profile?
    @State private var isContact: Bool?
    @State private var mutualCount: Int?
    @State private var lastActivity: Nostr_sdk_kmpTimestamp?
    @State private var subscription: FlowSubscription?

    private var member: Nostr_sdk_kmpPublicKey? {
        let selfHex = appState.bootstrap.currentPublicKey()?.toHex()
        return room.members.first { $0.toHex() != selfHex } ?? room.members.first
    }

    var body: some View {
        VStack(spacing: 16) {
            AvatarView(
                name: profile?.name ?? member?.short() ?? "?",
                picture: profile?.picture,
                size: 80
            )

            Text(profile?.name ?? member?.short() ?? "Unknown")
                .font(.headline)

            VStack(spacing: 8) {
                indicator(
                    title: "In your contacts",
                    value: isContact.map { $0 ? "Yes" : "No" },
                    positive: isContact == true
                )
                indicator(
                    title: "Mutual contacts",
                    value: mutualCount.map { "\($0)" },
                    positive: (mutualCount ?? 0) > 0
                )
                indicator(
                    title: "Last public activity",
                    value: lastActivity?.humanReadable(),
                    positive: lastActivity != nil
                )
            }
            .font(.subheadline)

            HStack(spacing: 12) {
                Button(role: .destructive, action: onReject) {
                    Text("Reject")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                Button(action: onAccept) {
                    Text("Accept")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .padding()
        .task {
            guard let member else { return }
            subscription = appState.bootstrap.watchProfile(pubkey: member) { value in
                Task { @MainActor in profile = value }
            }
            isContact = (try? await appState.bootstrap.verifyContact(pubkey: member))?.boolValue
            mutualCount = (try? await appState.bootstrap.mutualContacts(pubkey: member))?.count
            lastActivity = try? await appState.bootstrap.verifyActivity(pubkey: member)
        }
        .onDisappear {
            subscription?.cancel()
        }
    }

    private func indicator(title: String, value: String?, positive: Bool) -> some View {
        HStack {
            Image(systemName: positive ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(positive ? .green : .secondary)
            Text(title)
            Spacer()
            if let value {
                Text(value).foregroundStyle(.secondary)
            } else {
                ProgressView().controlSize(.mini)
            }
        }
    }
}
