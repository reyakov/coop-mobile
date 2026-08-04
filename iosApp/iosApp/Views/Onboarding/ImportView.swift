import SwiftUI

struct ImportView: View {
    @Environment(AppState.self) private var appState
    @State private var secret = ""
    @State private var password = ""
    @State private var showScanner = false

    private var needsPassword: Bool {
        secret.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("ncryptsec1")
    }

    private var canImport: Bool {
        !secret.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        (!needsPassword || !password.isEmpty) &&
        appState.accountState?.isImporting != true
    }

    var body: some View {
        Form {
            Section {
                HStack {
                    SecureField("nsec1... or bunker://", text: $secret)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button {
                        showScanner = true
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                    }
                }

                if needsPassword {
                    SecureField("Decrypt Password", text: $password)
                }
            } header: {
                Text("Secret Key")
            } footer: {
                Text("Enter your nsec, ncryptsec (with password), or bunker:// connection string.")
            }
        }
        .navigationTitle("Import Identity")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Import") {
                    appState.bootstrap.importIdentity(
                        secret: secret.trimmingCharacters(in: .whitespacesAndNewlines),
                        password: needsPassword ? password : nil
                    )
                }
                .disabled(!canImport)
            }
        }
        .overlay {
            if appState.accountState?.isImporting == true {
                ProgressView()
                    .controlSize(.large)
            }
        }
        .sheet(isPresented: $showScanner) {
            ScanView { result in
                secret = result
                showScanner = false
            }
        }
    }
}
