import SwiftUI
import Shared

private enum RelayRole: String, CaseIterable, Identifiable {
    case messaging = "Messaging"
    case inbox = "Inbox"
    case outbox = "Outbox"

    var id: String { rawValue }
}

struct RelayView: View {
    @Environment(AppState.self) private var appState
    @State private var lists: RelayLists?
    @State private var subscription: FlowSubscription?
    @State private var showAddRelay = false
    @State private var newRelay = ""
    @State private var newRelayRole = RelayRole.messaging
    @State private var addError: String?

    var body: some View {
        List {
            if let lists {
                if !lists.messaging.isEmpty {
                    Section("Messaging Relays") {
                        ForEach(lists.messaging, id: \.self) { relay in
                            relayRow(relay)
                                .swipeActions(edge: .trailing) {
                                    if lists.messaging.count > 1 {
                                        Button(role: .destructive) {
                                            appState.bootstrap.removeMsgRelay(relay: relay.description())
                                        } label: {
                                            Label("Remove", systemImage: "trash")
                                        }
                                    }
                                }
                        }
                    }
                }

                if !lists.inbox.isEmpty {
                    Section("Inbox Relays") {
                        ForEach(lists.inbox, id: \.self) { relay in
                            relayRow(relay)
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) {
                                        appState.bootstrap.removeRelay(relay: relay.description())
                                    } label: {
                                        Label("Remove", systemImage: "trash")
                                    }
                                }
                        }
                    }
                }

                if !lists.outbox.isEmpty {
                    Section("Outbox Relays") {
                        ForEach(lists.outbox, id: \.self) { relay in
                            relayRow(relay)
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) {
                                        appState.bootstrap.removeRelay(relay: relay.description())
                                    } label: {
                                        Label("Remove", systemImage: "trash")
                                    }
                                }
                        }
                    }
                }
            } else {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            }
        }
        .navigationTitle("Relays")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddRelay = true } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showAddRelay) {
            NavigationStack {
                Form {
                    Section("Relay URL") {
                        TextField("wss://relay.example.com", text: $newRelay)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                    }
                    Section("Role") {
                        Picker("Role", selection: $newRelayRole) {
                            ForEach(RelayRole.allCases) { role in
                                Text(role.rawValue).tag(role)
                            }
                        }
                        .pickerStyle(.inline)
                        .labelsHidden()
                    }
                    if let addError {
                        Section {
                            Text(addError).foregroundStyle(.red)
                        }
                    }
                }
                .navigationTitle("Add Relay")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            showAddRelay = false
                            newRelay = ""
                            addError = nil
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Add") {
                            addRelay()
                        }
                        .disabled(!newRelay.hasPrefix("wss://"))
                    }
                }
            }
            .presentationDetents([.medium])
        }
        .task {
            appState.bootstrap.loadRelayLists()
            subscription = appState.bootstrap.watchRelayLists { value in
                Task { @MainActor in lists = value }
            }
        }
        .onDisappear {
            subscription?.cancel()
        }
    }

    private func relayRow(_ relay: Nostr_sdk_kmpRelayUrl) -> some View {
        HStack {
            Image(systemName: "globe")
                .foregroundStyle(.secondary)
            Text(relay.description())
                .font(.subheadline)
                .lineLimit(1)
        }
    }

    private func addRelay() {
        let url = newRelay.trimmingCharacters(in: .whitespacesAndNewlines)
        guard url.hasPrefix("wss://") else {
            addError = "Relay URL must start with wss://"
            return
        }
        switch newRelayRole {
        case .messaging:
            appState.bootstrap.addMsgRelay(relay: url)
        case .inbox:
            appState.bootstrap.addInboxRelay(relay: url)
        case .outbox:
            appState.bootstrap.addOutboxRelay(relay: url)
        }
        showAddRelay = false
        newRelay = ""
        addError = nil
    }
}
