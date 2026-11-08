import Foundation

public struct PendingAction: Equatable {
    public let identifier: String
    public let title: String
    public let isReply: Bool

    public init(identifier: String, title: String, isReply: Bool) {
        self.identifier = identifier
        self.title = title
        self.isReply = isReply
    }
}

public struct PendingNotification: Equatable {
    public let identifier: String
    public let packageName: String
    public let title: String
    public let body: String
    public let subtitle: String
    public let categoryIdentifier: String
    public let isOngoing: Bool
    public let replyPlaceholder: String
    public let actions: [PendingAction]
    public var silent: Bool = false

    public init(
        identifier: String,
        packageName: String = "",
        title: String,
        body: String,
        subtitle: String,
        categoryIdentifier: String,
        isOngoing: Bool,
        replyPlaceholder: String,
        actions: [PendingAction]
    ) {
        self.identifier = identifier
        self.packageName = packageName
        self.title = title
        self.body = body
        self.subtitle = subtitle
        self.categoryIdentifier = categoryIdentifier
        self.isOngoing = isOngoing
        self.replyPlaceholder = replyPlaceholder
        self.actions = actions
    }
}

public protocol NotificationPresenting: AnyObject {
    func post(_ notification: PendingNotification)
    func remove(identifiers: [String])
    func removeAll()
}
