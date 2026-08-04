import SwiftUI

struct ProfileSheetView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    @State private var showLogoutConfirm = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 16) {
                        AvatarView(
                            name: appState.currentUserProfile?.name ?? "?",
                            picture: appState.currentUserProfile?.picture,
                            size: 64
                        )
                        VStack(alignment: .leading, spacing: 4) {
                            Text(appState.currentUserProfile?.name ?? "Loading...")
                                .font(.headline)
                            if let npub = try? appState.bootstrap.currentPublicKey()?.toBech32() {
                                Text(npub)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                        }
                    }
                    .padding(.vertical, 4)

                    Button {
                        dismiss()
                        appState.path.append(.myQr)
                    } label: {
                        Label("Show QR Code", systemImage: "qrcode")
                    }
                }

                Section {
                    Button {
                        dismiss()
                        appState.path.append(.updateProfile)
                    } label: {
                        Label("Update Profile", systemImage: "person.crop.circle")
                    }
                    Button {
                        dismiss()
                        appState.path.append(.contactList)
                    } label: {
                        Label("Contact List", systemImage: "person.2")
                    }
                    Button {
                        dismiss()
                        appState.path.append(.relay)
                    } label: {
                        Label("Relay Management", systemImage: "globe")
                    }
                    Button {
                        dismiss()
                        appState.path.append(.settings)
                    } label: {
                        Label("Settings", systemImage: "gear")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        showLogoutConfirm = true
                    } label: {
                        Label("Logout", systemImage: "rectangle.portrait.and.arrow.right")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
            .alert("Logout?", isPresented: $showLogoutConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Logout", role: .destructive) {
                    dismiss()
                    appState.logout()
                }
            } message: {
                Text("This will delete all local data. Make sure you have backed up your secret key.")
            }
        }
        .tint(.primary)
    }
}
