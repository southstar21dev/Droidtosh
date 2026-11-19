import Foundation

/// How long phone notifications stay quiet on the Mac.
public enum NotificationPause: CaseIterable, Equatable {
    case thirtyMinutes
    case oneHour
    case twoHours
    case tomorrowMorning
    case untilResumed

    public static let morningHour = 8

    public var title: String {
        switch self {
        case .thirtyMinutes: return "30 minutes"
        case .oneHour: return "1 hour"
        case .twoHours: return "2 hours"
        case .tomorrowMorning: return "Until tomorrow morning"
        case .untilResumed: return "Until I turn it back on"
        }
    }

    /// When the pause ends; `Date.distantFuture` for no end.
    public func endDate(from now: Date, calendar: Calendar = .current) -> Date {
        switch self {
        case .thirtyMinutes: return now.addingTimeInterval(30 * 60)
        case .oneHour: return now.addingTimeInterval(60 * 60)
        case .twoHours: return now.addingTimeInterval(2 * 60 * 60)
        case .untilResumed: return Date.distantFuture
        case .tomorrowMorning:
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: now) ?? now.addingTimeInterval(86_400)
            return calendar.date(
                bySettingHour: Self.morningHour, minute: 0, second: 0, of: tomorrow
            ) ?? tomorrow
        }
    }

    public static func isIndefinite(_ end: Date) -> Bool { end == .distantFuture }

    /// "Paused until 14:30", "Paused until tomorrow 08:00" or "Paused" for no end.
    public static func statusText(until end: Date, now: Date, calendar: Calendar = .current) -> String {
        if isIndefinite(end) { return "Paused" }
        let time = end.formatted(date: .omitted, time: .shortened)
        if calendar.isDate(end, inSameDayAs: now) { return "Paused until \(time)" }
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: now), calendar.isDate(end, inSameDayAs: tomorrow) {
            return "Paused until tomorrow \(time)"
        }
        let day = end.formatted(.dateTime.weekday(.abbreviated))
        return "Paused until \(day) \(time)"
    }
}
