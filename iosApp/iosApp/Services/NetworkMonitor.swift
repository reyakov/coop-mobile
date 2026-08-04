import Foundation
import Network

@Observable
final class NetworkMonitor {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "su.reya.coop.network")

    private(set) var isMobileData = false

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let mobile = path.usesInterfaceType(.cellular)
            Task { @MainActor in
                self?.isMobileData = mobile
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}
