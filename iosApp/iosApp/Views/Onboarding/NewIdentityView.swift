import SwiftUI

struct NewIdentityView: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        ProfileEditorForm(
            title: "Create a new identity",
            confirmLabel: "Continue",
            isSubmitting: appState.accountState?.isImporting == true
        ) { name, bio, picture, contentType in
            appState.bootstrap.createIdentity(
                name: name,
                bio: bio,
                picture: picture,
                contentType: contentType
            )
        }
    }
}
