import SwiftUI
import Shared

struct ContactListView: View {
    @Environment(AppState.self) private var appState
    @State private var showAddContact = false
    @State private var showScanner = false
    @State private var newContact = ""
    @State private var validating = false
    @State private var validationError: String?
    @State private var removing: Nostr_sdk_kmpPublicKey?

    private var contacts: [Nostr_sdk_kmpPublicKey] {
        Array(appState.accountState?.contactList ?? [])
            .sorted { $0.toHex() < $1.toHex() }
    }

    var body: some View {
        List {
            ForEach(contacts, id: \.self) { pubkey in
                Button {
                    openChat(with: pubkey)
                } label: {
                    ContactRow(pubkey: pubkey)
                }
                .tint(.primary)
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) {
                        removing = pubkey
                    } label: {
                        Label("Remove", systemImage: "trash")
                    }
                }
            }
        }
        .overlay {
            if contacts.isEmpty {
                ContentUnavailableView(
                    "No contacts",
                    systemImage: "person.2",
                    description: Text("Add contacts to start chatting")
                )
            }
        }
        .navigationTitle("Contacts")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack {
                    Button { showScanner = true } label: {
                        Image(systemName: "qrcode.viewfinder")
                    }
                    Button { showAddContact = true } label: {
                        Image(systemName: "plus")
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
        .alert("Add Contact", isPresented: $showAddContact) {
            TextField("npub or user@domain", text: $newContact)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Cancel", role: .cancel) {
                newContact = ""
                validationError = nil
            }
            Button("Add") {
                addContact()
            }
            .disabled(newContact.isEmpty || validating)
        } message: {
            if let validationError {
                Text(validationError)
            } else {
                Text("Enter a nostr public key (npub) or NIP-05 address")
            }
        }
        .alert("Remove Contact?", isPresented: Binding(
            get: { removing != nil },
            set: { if !$0 { removing = nil } }
        )) {
            Button("Cancel", role: .cancel) { removing = nil }
            Button("Remove", role: .destructive) {
                if let removing {
                    appState.bootstrap.removeContact(publicKey: removing)
                }
                removing = nil
            }
        } message: {
            Text("This contact will be removed from your list.")
        }
    }

    private func addContact() {
        let value = newContact.trimmingCharacters(in: .whitespacesAndNewlines)
        validating = true
        Task {
            defer { validating = false }
            if appState.bootstrap.parsePublicKey(input: value) != nil {
                appState.bootstrap.addContact(address: value)
                newContact = ""
            } else if value.contains("@"),
                      (try? await appState.bootstrap.searchByAddress(query: value)) != nil {
                appState.bootstrap.addContact(address: value)
                newContact = ""
            } else {
                validationError = "Could not find this user. Check the address and try again."
            }
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
}
