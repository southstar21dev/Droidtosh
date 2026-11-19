import XCTest
@testable import DroidsyncKit

final class MotionPolicyTests: XCTestCase {
    func testAnimatesWhenVisibleAndMotionAllowed() {
        XCTAssertTrue(MotionPolicy.shouldAnimate(reduceMotion: false, onScreen: true))
    }

    func testReduceMotionHoldsStill() {
        XCTAssertFalse(MotionPolicy.shouldAnimate(reduceMotion: true, onScreen: true))
    }

    func testHiddenHoldsStill() {
        XCTAssertFalse(MotionPolicy.shouldAnimate(reduceMotion: false, onScreen: false))
        XCTAssertFalse(MotionPolicy.shouldAnimate(reduceMotion: true, onScreen: false))
    }

    func testFrameCap() {
        XCTAssertEqual(MotionPolicy.frameInterval(size: 28, steady: false), 1.0 / 30)
        XCTAssertEqual(MotionPolicy.frameInterval(size: 96, steady: false), 1.0 / 60)
        XCTAssertEqual(MotionPolicy.frameInterval(size: 96, steady: true), 1.0 / 30)
    }
}
