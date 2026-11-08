import Foundation

public func protocolEnvelope(_ payload: DSEnvelope.OneOf_Payload) -> DSEnvelope {
    var envelope = DSEnvelope()
    envelope.protocolVersion = ProtocolV2.version
    envelope.messageID = UUID().uuidString
    envelope.sentAtMs = UInt64(Date().timeIntervalSince1970 * 1000)
    envelope.payload = payload
    return envelope
}
