import Foundation

public final class MuteListStore {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "muted-packages") {
        self.defaults = defaults
        self.key = key
    }

    public var mutedPackages: Set<String> {
        Set(defaults.stringArray(forKey: key) ?? [])
    }

    public func setMuted(_ package: String, _ muted: Bool) {
        var current = mutedPackages
        if muted {
            current.insert(package)
        } else {
            current.remove(package)
        }
        defaults.set(Array(current).sorted(), forKey: key)
    }

    public func setAll(_ packages: Set<String>) {
        defaults.set(Array(packages).sorted(), forKey: key)
    }
}
