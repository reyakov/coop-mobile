import SwiftUI
import Shared

struct SettingsView: View {
    @Environment(AppState.self) private var appState
    @State private var blossomServer = ""

    private var settings: Settings? {
        appState.settings
    }

    var body: some View {
        Form {
            Section("General") {
                Toggle("Filter unknown contacts", isOn: Binding(
                    get: { settings?.screening == true },
                    set: { appState.bootstrap.setScreening(enabled: $0) }
                ))

                Picker("Media Preview", selection: Binding(
                    get: { settings?.media ?? MediaConfig.alwaysenabled },
                    set: { appState.bootstrap.setMediaConfig(media: $0) }
                )) {
                    Text("Disabled").tag(MediaConfig.disabled)
                    Text("Disabled for Mobile Data").tag(MediaConfig.disabledformobiledata)
                    Text("Always Enabled").tag(MediaConfig.alwaysenabled)
                }

                HStack {
                    Text("Blossom Server")
                    Spacer()
                    TextField("https://blossom.band", text: $blossomServer)
                        .multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .foregroundStyle(.secondary)
                        .onSubmit {
                            appState.bootstrap.setBlossomServer(
                                url: blossomServer.isEmpty ? nil : blossomServer
                            )
                        }
                }

                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Label("Notifications", systemImage: "bell")
                }
                .tint(.primary)
            }

            Section("Appearance") {
                Picker("Theme", selection: Binding(
                    get: { settings?.theme ?? Theme.system },
                    set: { appState.bootstrap.setTheme(theme: $0) }
                )) {
                    Text("Light").tag(Theme.light)
                    Text("Dark").tag(Theme.dark)
                    Text("System").tag(Theme.system)
                }
            }
        }
        .navigationTitle("Settings")
        .onAppear {
            blossomServer = settings?.blossomServer ?? ""
        }
    }
}
