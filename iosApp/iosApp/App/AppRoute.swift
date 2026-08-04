import Foundation

enum AppRoute: Hashable {
    case home
    case requestList
    case contactList
    case updateProfile
    case newChat
    case myQr
    case relay
    case settings
    case chat(id: Int64, screening: Bool)
    case profile(pubkey: String)
}
