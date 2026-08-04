import SwiftUI
import Shared

struct UpdateProfileView: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    private var record: Nostr_sdk_kmpMetadataRecord? {
        appState.currentUserProfile?.metadata.asRecord()
    }

    var body: some View {
        ProfileEditorForm(
            title: "Update Profile",
            confirmLabel: "Save changes",
            initialName: record?.displayName ?? record?.name ?? "",
            initialBio: record?.about ?? "",
            initialPicture: appState.currentUserProfile?.picture,
            isSubmitting: appState.isUpdatingProfile
        ) { name, bio, picture, contentType in
            appState.bootstrap.updateProfile(
                name: name,
                bio: bio,
                picture: picture,
                contentType: contentType
            )
            dismiss()
        }
    }
}
