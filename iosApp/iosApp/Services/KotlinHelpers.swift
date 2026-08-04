import Foundation
import Shared

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}

extension KotlinBoolean {
    var value: Bool { boolValue }
}

extension String {
    func isImageUrl() -> Bool {
        ExtensionsKt.isImageUrl(self)
    }

    func removeImageUrls() -> String {
        ExtensionsKt.removeImageUrls(self)
    }
}
