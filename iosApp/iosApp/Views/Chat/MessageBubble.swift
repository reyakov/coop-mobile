import SwiftUI
import Shared

struct MessageBubble: View {
    let event: Nostr_sdk_kmpUnsignedEvent
    let isMine: Bool
    let showImages: Bool
    let isFirstOfRun: Bool
    let isLastOfRun: Bool
    let showAuthorName: Bool
    let showAuthorAvatar: Bool
    let authorName: String?
    let authorPicture: String?
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
        HStack(alignment: .bottom, spacing: 6) {
            if isMine {
                Spacer(minLength: 48)
            } else {
                avatarGutter
            }

            VStack(alignment: isMine ? .trailing : .leading, spacing: 2) {
                if showAuthorName, let authorName {
                    Text(authorName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.leading, 16)
                }

                if let repliedMessage {
                    replyPreview(repliedMessage)
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
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }

                if !text.isEmpty {
                    Text(text)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .foregroundStyle(isMine ? .white : .primary)
                        .background(
                            isMine ? Color(.systemBlue) : Color(.systemGray5),
                            in: BubbleShape(isMine: isMine, tail: isLastOfRun)
                        )
                }

                if showTimestamp {
                    Text(event.createdAt().formatAsTime())
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 16)
                }
            }

            if !isMine { Spacer(minLength: 48) }
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
    }

    @ViewBuilder
    private var avatarGutter: some View {
        if isLastOfRun, showAuthorAvatar {
            AvatarView(name: authorName ?? "?", picture: authorPicture, size: 26)
        } else {
            Color.clear.frame(width: 26, height: 26)
        }
    }

    private func replyPreview(_ replied: Nostr_sdk_kmpUnsignedEvent) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(repliedAuthorName ?? "Unknown")
                .font(.caption2.bold())
            Text(replied.content())
                .font(.caption)
                .lineLimit(2)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            isMine ? Color(.systemBlue).opacity(0.7) : Color(.systemGray4),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .foregroundStyle(isMine ? .white : .primary)
        .padding(.horizontal, 4)
    }
}

struct BubbleShape: Shape {
    let isMine: Bool
    let tail: Bool

    func path(in rect: CGRect) -> Path {
        let radius: CGFloat = 18
        let small: CGFloat = tail ? 4 : radius

        let topLeft: CGFloat = radius
        let topRight: CGFloat = radius
        let bottomLeft: CGFloat = isMine ? radius : small
        let bottomRight: CGFloat = isMine ? small : radius

        var path = Path()
        path.move(to: CGPoint(x: rect.minX + topLeft, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - topRight, y: rect.minY))
        path.addArc(
            center: CGPoint(x: rect.maxX - topRight, y: rect.minY + topRight),
            radius: topRight, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - bottomRight))
        path.addArc(
            center: CGPoint(x: rect.maxX - bottomRight, y: rect.maxY - bottomRight),
            radius: bottomRight, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.minX + bottomLeft, y: rect.maxY))
        path.addArc(
            center: CGPoint(x: rect.minX + bottomLeft, y: rect.maxY - bottomLeft),
            radius: bottomLeft, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + topLeft))
        path.addArc(
            center: CGPoint(x: rect.minX + topLeft, y: rect.minY + topLeft),
            radius: topLeft, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false
        )
        path.closeSubpath()
        return path
    }
}
