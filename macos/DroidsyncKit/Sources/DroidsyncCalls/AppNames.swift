import Foundation

/// Human names for Android apps, for call captions like "Incoming WhatsApp Business call".
public enum AppNames {
    /// Apps whose names we know without asking the phone.
    public static let known: [String: String] = [
        "com.whatsapp": "WhatsApp",
        "com.whatsapp.w4b": "WhatsApp Business",
        "org.telegram.messenger": "Telegram",
        "org.thoughtcrime.securesms": "Signal",
        "com.facebook.orca": "Messenger",
        "com.google.android.apps.meetings": "Google Meet",
        "us.zoom.xos": "Zoom",
        "com.viber.voip": "Viber",
        "com.skype.raider": "Skype",
        "com.microsoft.teams": "Microsoft Teams",
        "com.discord": "Discord",
        "com.instagram.android": "Instagram",
        "com.google.android.apps.tachyon": "Google Meet",
        "jp.naver.line.android": "LINE",
        "com.tencent.mm": "WeChat",
    ]

    /// The phone's own label for `packageName` (from its app list or notifications), then a known
    /// name, then a name made from the package. Never the raw package.
    public static func displayName(for packageName: String, labels: [String: String] = [:]) -> String {
        if let label = labels[packageName]?.trimmingCharacters(in: .whitespaces),
           !label.isEmpty, label != packageName {
            return label
        }
        if let name = known[packageName] { return name }
        return prettified(packageName)
    }

    /// "com.viber.voip" → "Viber", "org.example.chatapp" → "Chatapp".
    static func prettified(_ packageName: String) -> String {
        let generic: Set<String> = [
            "com", "org", "net", "io", "app", "apps", "android", "mobile", "voip", "client", "free",
            "lite", "messenger", "main", "release", "prod",
        ]
        let parts = packageName.split(separator: ".").map(String.init)
        let meaningful = parts.dropFirst().filter { !generic.contains($0.lowercased()) && !$0.isEmpty }
        guard let word = meaningful.first ?? parts.last, !word.isEmpty else { return "App" }
        return word.prefix(1).uppercased() + word.dropFirst()
    }
}
