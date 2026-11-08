import Foundation

enum MacIdentity {
    static func current() -> (name: String, model: String, identifier: String) {
        let name = Host.current().localizedName ?? "Mac"
        let identifier = run("/usr/sbin/sysctl", ["-n", "hw.model"]) ?? ""
        let model = systemProfilerModelName() ?? familyName(for: identifier) ?? (identifier.isEmpty ? "Mac" : identifier)
        return (name, model, identifier)
    }

    private static func familyName(for identifier: String) -> String? {
        if identifier.hasPrefix("MacBookPro") { return "MacBook Pro" }
        if identifier.hasPrefix("MacBookAir") { return "MacBook Air" }
        if identifier.hasPrefix("MacBook") { return "MacBook" }
        if identifier.hasPrefix("Macmini") { return "Mac mini" }
        if identifier.hasPrefix("iMac") { return "iMac" }
        if identifier.hasPrefix("MacPro") { return "Mac Pro" }
        if identifier.hasPrefix("Mac") { return "Mac" }
        return nil
    }

    private static func systemProfilerModelName() -> String? {
        guard let output = run("/usr/sbin/system_profiler", ["SPHardwareDataType", "-json"]),
              let data = output.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = object["SPHardwareDataType"] as? [[String: Any]],
              let model = items.first?["model_name"] as? String,
              !model.isEmpty else { return nil }
        return model
    }

    private static func run(_ executable: String, _ arguments: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe()
        guard (try? process.run()) != nil else { return nil }
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
