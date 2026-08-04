import Foundation
import Security
import Shared

final class IosAppStorage: AppStorage {
    private let defaults = UserDefaults.standard
    private let service = "su.reya.coop"

    func get(key: String, completionHandler: @escaping (String?, Error?) -> Void) {
        completionHandler(defaults.string(forKey: key), nil)
    }

    func set(key: String, value: String, completionHandler: @escaping (Error?) -> Void) {
        defaults.set(value, forKey: key)
        completionHandler(nil)
    }

    func getSecret(key: String, completionHandler: @escaping (String?, Error?) -> Void) {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            completionHandler(nil, nil)
            return
        }
        completionHandler(String(data: data, encoding: .utf8), nil)
    }

    func setSecret(key: String, value: String, completionHandler: @escaping (Error?) -> Void) {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
        ]
        if SecItemCopyMatching(query as CFDictionary, nil) == errSecSuccess {
            SecItemUpdate(query as CFDictionary, [kSecValueData: data] as CFDictionary)
        } else {
            var add = query
            add[kSecValueData] = data
            add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
        completionHandler(nil)
    }

    func clear(key: String, completionHandler: @escaping (Error?) -> Void) {
        defaults.removeObject(forKey: key)
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
        ]
        SecItemDelete(query as CFDictionary)
        completionHandler(nil)
    }

    func has(key: String, completionHandler: @escaping (KotlinBoolean?, Error?) -> Void) {
        if defaults.object(forKey: key) != nil {
            completionHandler(KotlinBoolean(booleanLiteral: true), nil)
            return
        }
        getSecret(key: key) { secret, _ in
            completionHandler(KotlinBoolean(booleanLiteral: secret != nil), nil)
        }
    }
}
