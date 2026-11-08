import Foundation

/// Builds before the identifier rename shipped as `app.droidtosh.mac`, so their settings (relay
/// host, port and fingerprint, feature toggles, dedup and mute lists) live in that defaults domain.
/// On the first launch under the new bundle ID, while the new domain has no relay settings yet,
/// this copies every key the new domain lacks from the old one. The old domain is left in place.
///
/// Secrets aren't migrated: they live in `SecretStore` files, and keychain-based builds are gone.
/// Delete this file a few releases after the rename.
enum LegacyDefaultsMigration {
    static let legacyDomain = "app.droidtosh.mac"

    static func run(defaults: UserDefaults = .standard) {
        #if DEBUG
        if SnapshotRenderer.isActive { return }
        #endif
        guard defaults.object(forKey: "relayHost") == nil,
              let old = defaults.persistentDomain(forName: legacyDomain),
              !old.isEmpty else { return }
        var copied = 0
        for (key, value) in old where defaults.object(forKey: key) == nil {
            defaults.set(value, forKey: key)
            copied += 1
        }
        DebugLog.log("defaults: copied \(copied) settings from \(legacyDomain)")
    }
}
