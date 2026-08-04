import SwiftUI
import VisionKit

struct ScanView: View {
    @Environment(\.dismiss) private var dismiss
    let onResult: (String) -> Void

    var body: some View {
        ZStack(alignment: .top) {
            if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                ScannerRepresentable(onResult: onResult)
                    .ignoresSafeArea()
            } else {
                ContentUnavailableView(
                    "Scanner unavailable",
                    systemImage: "camera.fill",
                    description: Text("This device does not support the data scanner")
                )
            }

            VStack {
                Spacer()
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(Color.white, lineWidth: 3)
                    .frame(width: 250, height: 250)
                    .background(.clear)
                Spacer()
            }
            .allowsHitTesting(false)
        }
        .overlay(alignment: .topLeading) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white)
                    .padding()
            }
        }
    }
}

private struct ScannerRepresentable: UIViewControllerRepresentable {
    let onResult: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onResult: onResult)
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onResult: (String) -> Void
        private var handled = false

        init(onResult: @escaping (String) -> Void) {
            self.onResult = onResult
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            guard !handled,
                  case .barcode(let barcode) = addedItems.first,
                  let payload = barcode.payloadStringValue
            else { return }
            handled = true
            onResult(payload)
        }
    }
}
