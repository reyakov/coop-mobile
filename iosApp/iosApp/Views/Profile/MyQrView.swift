import CoreImage.CIFilterBuiltins
import SwiftUI

struct MyQrView: View {
    @Environment(AppState.self) private var appState

    private var npub: String? {
        try? appState.bootstrap.currentPublicKey()?.toBech32()
    }

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            if let npub, let qrImage = generateQrCode(from: npub) {
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 280)
                    .padding()
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 16))

                Text(npub)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                ShareLink(item: npub) {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.bordered)
            } else {
                ContentUnavailableView("No identity", systemImage: "qrcode")
            }

            Spacer()
        }
        .padding()
        .navigationTitle("My QR Code")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func generateQrCode(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let outputImage = filter.outputImage else { return nil }
        let scaled = outputImage.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
