import SwiftUI
import Shared

struct ContactRow: View {
    @Environment(AppState.self) private var appState
    let pubkey: Nostr_sdk_kmpPublicKey
    @State private var profile: Profile?
    @State private var subscription: FlowSubscription?

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: profile?.name ?? "?", picture: profile?.picture)
            VStack(alignment: .leading, spacing: 2) {
                Text(profile?.name ?? "Loading...")
                    .font(.headline)
                    .lineLimit(1)
                Text(pubkey.short())
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 2)
        .task {
            subscription = appState.bootstrap.watchProfile(pubkey: pubkey) { value in
                Task { @MainActor in profile = value }
            }
        }
        .onDisappear {
            subscription?.cancel()
            subscription = nil
        }
    }
}

struct NewChatView: View {
    @Environment(AppState.self) private var appState
    @State private var query = ""
    @State private var searchResults: [Nostr_sdk_kmpPublicKey] = []
    @State private var searching = false
    @State private var selected: [Nostr_sdk_kmpPublicKey] = []
    @State private var showScanner = false
    @State private var searchTask: Task<Void, Never>?

    private var contacts: [Nostr_sdk_kmpPublicKey] {
        Array(appState.accountState?.contactList ?? [])
    }

    var body: some View {
        List {
            if !selected.isEmpty {
                Section("To:") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(selected, id: \.self) { pubkey in
                                Button {
                                    selected.removeAll { $0.toHex() == pubkey.toHex() }
                                } label: {
                                    Label(pubkey.short(), systemImage: "xmark.circle.fill")
                                        .font(.subheadline)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .background(Color(.secondarySystemBackground), in: Capsule())
                                }
                                .tint(.primary)
                            }
                        }
                    }
                }
            }

            if searching {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            }

            let results = query.isEmpty ? contacts : searchResults
            Section(query.isEmpty ? "Contacts" : "Results") {
                ForEach(results, id: \.self) { pubkey in
                    Button {
                        openChat(with: pubkey)
                    } label: {
                        ContactRow(pubkey: pubkey)
                    }
                    .tint(.primary)
                    .swipeActions(edge: .leading) {
                        Button {
                            toggleSelection(pubkey)
                        } label: {
                            Label("Select", systemImage: "checkmark.circle")
                        }
                        .tint(.accentColor)
                    }
                }
            }
        }
        .navigationTitle("New Chat")
        .searchable(text: $query, prompt: "npub, user@domain, or name")
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .onChange(of: query) { _, newValue in
            searchTask?.cancel()
            searchTask = Task {
                try? await Task.sleep(for: .milliseconds(500))
                guard !Task.isCancelled else { return }
                await performSearch(newValue)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack {
                    Button {
                        showScanner = true
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                    }
                    if !selected.isEmpty {
                        Button("Next") {
                            createGroupChat()
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $showScanner) {
            ScanView { result in
                showScanner = false
                if let pubkey = appState.bootstrap.parsePublicKey(input: result) {
                    openChat(with: pubkey)
                } else {
                    appState.errorMessage = "Invalid public key"
                }
            }
        }
    }

    private func performSearch(_ value: String) async {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 3 else {
            searchResults = []
            return
        }

        searching = true
        defer { searching = false }

        if trimmed.hasPrefix("npub1") {
            if let pubkey = appState.bootstrap.parsePublicKey(input: trimmed) {
                searchResults = [pubkey]
            }
        } else if trimmed.contains("@") {
            if let pubkey = try? await appState.bootstrap.searchByAddress(query: trimmed) {
                searchResults = [pubkey]
            } else {
                searchResults = []
            }
        } else {
            searchResults = (try? await appState.bootstrap.searchByNostr(query: trimmed)) ?? []
        }
    }

    private func toggleSelection(_ pubkey: Nostr_sdk_kmpPublicKey) {
        if let index = selected.firstIndex(where: { $0.toHex() == pubkey.toHex() }) {
            selected.remove(at: index)
        } else {
            selected.append(pubkey)
        }
    }

    private func openChat(with pubkey: Nostr_sdk_kmpPublicKey) {
        do {
            let roomId = try appState.bootstrap.createChatRoom(recipients: [pubkey])
            appState.path.append(.chat(id: roomId, screening: false))
        } catch {
            appState.errorMessage = error.localizedDescription
        }
    }

    private func createGroupChat() {
        do {
            let roomId = try appState.bootstrap.createChatRoom(recipients: selected)
            appState.path.append(.chat(id: roomId, screening: false))
        } catch {
            appState.errorMessage = error.localizedDescription
        }
    }
}
