import PhotosUI
import SwiftUI
import Shared

struct ProfileEditorForm: View {
    let title: String
    let confirmLabel: String
    let initialName: String
    let initialBio: String
    let initialPicture: String?
    let isSubmitting: Bool
    let onConfirm: (String, String?, KotlinByteArray?, String?) -> Void

    @State private var name: String
    @State private var bio: String
    @State private var photoItem: PhotosPickerItem?
    @State private var photoData: Data?
    @State private var photoContentType: String?

    init(
        title: String,
        confirmLabel: String,
        initialName: String = "",
        initialBio: String = "",
        initialPicture: String? = nil,
        isSubmitting: Bool = false,
        onConfirm: @escaping (String, String?, KotlinByteArray?, String?) -> Void
    ) {
        self.title = title
        self.confirmLabel = confirmLabel
        self.initialName = initialName
        self.initialBio = initialBio
        self.initialPicture = initialPicture
        self.isSubmitting = isSubmitting
        self.onConfirm = onConfirm
        _name = State(initialValue: initialName)
        _bio = State(initialValue: initialBio)
    }

    var body: some View {
        Form {
            Section {
                HStack {
                    Spacer()
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        if let photoData, let uiImage = UIImage(data: photoData) {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 120, height: 120)
                                .clipShape(Circle())
                        } else {
                            AvatarView(name: name.isEmpty ? "?" : name, picture: initialPicture, size: 120)
                                .overlay(alignment: .bottomTrailing) {
                                    Image(systemName: "plus.circle.fill")
                                        .font(.title2)
                                        .foregroundStyle(.tint)
                                }
                        }
                    }
                    Spacer()
                }
            }
            .listRowBackground(Color.clear)

            Section {
                TextField("What others should call you?", text: $name)
                TextField("Tell others about yourself (optional)", text: $bio, axis: .vertical)
                    .lineLimit(3...6)
            }

            Section {
                Button {
                    onConfirm(
                        name.trimmingCharacters(in: .whitespacesAndNewlines),
                        bio.isEmpty ? nil : bio,
                        photoData?.toKotlinByteArray(),
                        photoContentType
                    )
                } label: {
                    HStack {
                        Spacer()
                        if isSubmitting {
                            ProgressView()
                        } else {
                            Text(confirmLabel)
                        }
                        Spacer()
                    }
                }
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSubmitting)
            }
        }
        .navigationTitle(title)
        .task(id: photoItem) {
            guard let photoItem else { return }
            if let data = try? await photoItem.loadTransferable(type: Data.self) {
                photoData = data
                photoContentType = photoItem.supportedContentTypes.first?.preferredMIMEType ?? "image/jpeg"
            }
        }
    }
}
