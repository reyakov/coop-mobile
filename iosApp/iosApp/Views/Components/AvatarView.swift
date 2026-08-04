import SwiftUI

struct AvatarView: View {
    let name: String
    let picture: String?
    var size: CGFloat = 44

    var body: some View {
        Group {
            if let picture, let url = URL(string: picture), !picture.isEmpty {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                    default:
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    private var placeholder: some View {
        ZStack {
            Circle().fill(Color(.secondarySystemFill))
            Text(name.prefix(1).uppercased())
                .font(.system(size: size * 0.45, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }
}
