import XCTest
@testable import DroidsyncNotifications

final class DedupEngineTests: XCTestCase {
    func testSuppressesWhenMappedDesktopAppIsRunning() {
        let engine = DedupEngine(runningApps: { ["net.whatsapp.WhatsApp"] })
        XCTAssertTrue(engine.shouldSuppress(androidPackage: "com.whatsapp"))
        XCTAssertFalse(engine.shouldSuppress(androidPackage: "org.telegram.messenger"))
    }

    func testForwardsWhenDesktopAppNotRunning() {
        let engine = DedupEngine(runningApps: { ["com.apple.Safari"] })
        XCTAssertFalse(engine.shouldSuppress(androidPackage: "com.whatsapp"))
    }

    func testRespectsDisabledPackagesAndMasterSwitch() {
        var config = DedupConfig()
        config.disabledPackages = ["com.whatsapp"]
        let disabled = DedupEngine(config: config, runningApps: { ["net.whatsapp.WhatsApp"] })
        XCTAssertFalse(disabled.shouldSuppress(androidPackage: "com.whatsapp"))

        var off = DedupConfig()
        off.enabled = false
        let masterOff = DedupEngine(config: off, runningApps: { ["net.whatsapp.WhatsApp"] })
        XCTAssertFalse(masterOff.shouldSuppress(androidPackage: "com.whatsapp"))
    }

    func testWhatsAppBusinessIsNeverSuppressed() {
        // No desktop app exists for WhatsApp Business, so its calls must always ring here,
        // even with the regular WhatsApp desktop app running.
        let running: Set<String> = ["net.whatsapp.WhatsApp", "desktop.WhatsApp"]
        let presets = DedupEngine(runningApps: { running })
        XCTAssertNil(DedupConfig.presets["com.whatsapp.w4b"])
        XCTAssertFalse(presets.shouldSuppress(androidPackage: "com.whatsapp.w4b"))
        XCTAssertTrue(presets.shouldSuppress(androidPackage: "com.whatsapp"))

        // Not even through a mapping to the WhatsApp desktop app.
        var mapped = DedupConfig()
        mapped.mappings["com.whatsapp.w4b"] = ["net.whatsapp.WhatsApp"]
        let engine = DedupEngine(config: mapped, runningApps: { running })
        XCTAssertFalse(engine.shouldSuppress(androidPackage: "com.whatsapp.w4b"))
        engine.update(config: mapped)
        XCTAssertFalse(engine.shouldSuppress(androidPackage: "com.whatsapp.w4b"))
        XCTAssertTrue(DedupConfig.neverSuppressed.contains("com.whatsapp.w4b"))
    }

    func testTelegramDesktopBundleIDsCovered() {
        let engine = DedupEngine(runningApps: { ["org.telegram.desktop"] })
        XCTAssertTrue(engine.shouldSuppress(androidPackage: "org.telegram.messenger"))
    }

    func testTelegramDirectDownloadBundleIDCovered() {
        // The Telegram app downloaded from telegram.org is /Applications/Telegram.app
        // with bundle identifier com.tdesktop.Telegram.
        let engine = DedupEngine(runningApps: { ["com.tdesktop.Telegram"] })
        XCTAssertTrue(engine.shouldSuppress(androidPackage: "org.telegram.messenger"))
    }
}
