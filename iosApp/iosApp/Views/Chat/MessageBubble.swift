import SwiftUI
import Shared

struct MessageBubble: View {
    let event: Nostr_sdk_kmpUnsignedEvent
    let isMine: Bool
    let showImages: Bool
    let repliedMessage: Nostr_sdk_kmpUnsignedEvent?
    let repliedAuthorName: String?
    let onReply: () -> Void

    @State private var showTimestamp = false

    private var imageUrls: [URL] {
        guard showImages else { return [] }
        return event.content()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { $0.isImageUrl() }
            .compactMap { URL(string: $0) }
    }

    private var text: String {
        showImages ? event.content().removeImageUrls() : event.content()
    }

    var body: some View {
        HStack {
            if isMine { Spacer(minLength: 40) }

            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                if let repliedMessage {
                    HStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.accentColor)
                            .frame(width: 3)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(repliedAuthorName ?? "Unknown")
                                .font(.caption.bold())
                            Text(repliedMessage.content())
                                .font(.caption)
                                .lineLimit(2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(8)
                    .background(.black.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                }

                ForEach(imageUrls, id: \.absoluteString) { url in
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFit()
                        case .failure:
                            ContentUnavailableView("Image unavailable", systemImage: "photo.badge.exclamationmark")
                                .frame(height: 120)
                        default:
                            ProgressView()
                                .frame(height: 120)
                        }
                    }
                    .frame(maxWidth: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }

                if !text.isEmpty {
                    Text(text)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(
                            isMine ? Color.accentColor : Color(.secondarySystemBackground),
                            in: BubbleShape(isMine: isMine)
                        )
                        .foregroundStyle(isMine ? .white : .primary)
                }

                if showTimestamp {
                    Text(event.createdAt().formatAsTime())
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.15)) {
                    showTimestamp.toggle()
                }
            }
            .contextMenu {
                Button {
                    UIPasteboard.general.string = event.content()
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }
                Button {
                    onReply()
                } label: {
                    Label("Reply", systemImage: "arrowshape.turn.up.left")
                }
            }

            if !isMine { Spacer(minLength: 40) }
        }
    }
}

struct BubbleShape: Shape {
    let isMine: Bool

    func path(in rect: CGRect) -> Path {
        let radius: CGFloat = 16
        let tail: CGFloat = 4
        let corners: UIRectCorner = isMine
            ? [.topLeft, .topRight, .bottomLeft]
            : [.topLeft, .topRight, .bottomRight]

        let path = UIBezierPath(
            roundedRect: rect,
            byRoundingCorners: corners,
            cornerRadii: CGSize(width: radius, height: radius)
        )
        _ = tail
        return Path(path.cgPath)
    }
}
