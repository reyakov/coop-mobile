import SwiftUI

struct RelayWarningSheet: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 48))
                .foregroundStyle(.orange)

            Text("No Messaging Relays")
                .font(.title2.bold())

            Text("You don't have any messaging relays configured. You won't be able to receive messages until this is resolved.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            VStack(spacing: 12) {
                Button {
                    appState.bootstrap.refetchMsgRelays()
                    dismiss()
                } label: {
                    Text("Retry")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

                Button {
                    appState.bootstrap.useDefaultMsgRelayList()
                    dismiss()
                } label: {
                    Text("Use Default")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }
        }
        .padding(32)
        .presentationDetents([.medium])
    }
}
