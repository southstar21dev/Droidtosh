import XCTest
@testable import DroidsyncTransport

final class AuthTests: XCTestCase {
    func testMacInputLayout() {
        let input = Auth.macInput(role: "client", serverNonce: Data([1, 2]), clientNonce: Data([3, 4]))
        XCTAssertEqual(input, Data("client".utf8) + Data([1, 2, 3, 4]))
    }

    func testMacIsDeterministicAndRoleSeparated() {
        let psk = Data(repeating: 0x11, count: 32)
        let server = Data(repeating: 0x22, count: 32)
        let client = Data(repeating: 0x33, count: 32)
        let clientMAC = Auth.mac(psk: psk, role: "client", serverNonce: server, clientNonce: client)
        XCTAssertEqual(clientMAC.count, 32)
        XCTAssertEqual(clientMAC, Auth.mac(psk: psk, role: "client", serverNonce: server, clientNonce: client))
        XCTAssertNotEqual(clientMAC, Auth.mac(psk: psk, role: "server", serverNonce: server, clientNonce: client))
    }

    func testConstantTimeEquals() {
        XCTAssertTrue(Auth.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 3])))
        XCTAssertFalse(Auth.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 4])))
        XCTAssertFalse(Auth.constantTimeEquals(Data([1, 2]), Data([1, 2, 3])))
    }

    func testMacMatchesRFC4231TestCase1() {
        let expected = hexDecoded("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
        let mac = Auth.mac(
            psk: Data(repeating: 0x0b, count: 20),
            role: "",
            serverNonce: Data(),
            clientNonce: Data("Hi There".utf8)
        )
        XCTAssertEqual(mac, expected)
    }

    private func hexDecoded(_ hex: String) -> Data {
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return Data(bytes)
    }
}
