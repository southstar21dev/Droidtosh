import XCTest
@testable import DroidsyncNotifications

final class VerificationCodeDetectorTests: XCTestCase {
    private func code(_ text: String) -> String? { VerificationCodeDetector.code(in: text) }

    func testCommonEnglishForms() {
        XCTAssertEqual(code("Your verification code is 482913"), "482913")
        XCTAssertEqual(code("482913 is your Uber code. Never share it."), "482913")
        XCTAssertEqual(code("G-123456 is your Google verification code."), "123456")
        XCTAssertEqual(code("Use OTP 7731 to log in"), "7731")
        XCTAssertEqual(code("Your PIN: 90210"), "90210")
        XCTAssertEqual(code("Your passcode is 12345678."), "12345678")
    }

    func testSplitCodesAreJoined() {
        XCTAssertEqual(code("Your code is 123 456"), "123456")
        XCTAssertEqual(code("Verification code: 123-456"), "123456")
        XCTAssertEqual(code("code 1234 5678"), "12345678")
    }

    func testOtherLanguages() {
        XCTAssertEqual(code("Tu código de verificación es 554433"), "554433")
        XCTAssertEqual(code("Ihr Bestätigungscode lautet 880011"), "880011")
        XCTAssertEqual(code("Votre code de vérification : 3321"), "3321")
        XCTAssertEqual(code("Ваш код подтверждения 667788"), "667788")
        XCTAssertEqual(code("您的验证码123456，请勿泄露"), "123456")
    }

    func testTitleAndBody() {
        XCTAssertEqual(VerificationCodeDetector.code(title: "Verification code", body: "482913"), "482913")
    }

    func testNeedsAKeyword() {
        XCTAssertNil(code("Call me on 555 1234 tomorrow"))
        XCTAssertNil(code("Your order 123456 has shipped"))
        XCTAssertNil(code("Meeting moved to 2025"))
        XCTAssertNil(code("Balance: 1234.56"))
    }

    func testRejectsOtherNumbersEvenWithAKeyword() {
        XCTAssertNil(code("Your code was sent to +15551234567"))
        XCTAssertNil(code("Code applied, you saved $1234"))
        XCTAssertNil(code("Code expires on 2025-12-05"))
        XCTAssertNil(code("Verification code sent 12/05/2025"))
        XCTAssertNil(code("Your PIN is valid for 10000 minutes"))
        XCTAssertNil(code("Promo code for 1500 EUR"))
        XCTAssertNil(code("Code at 12:30 pm"))
        XCTAssertNil(code("Order #123456 code accepted"))
    }

    func testPicksTheCodeNotTheOrderNumber() {
        XCTAssertEqual(code("Order 5555 verification code 482913"), "482913")
    }

    func testYearOnlyWhenKeywordIsRightBesideIt() {
        XCTAssertEqual(code("Your code is 2024"), "2024")
        XCTAssertNil(code("Your code for the conference held in 2024 is not needed anymore, see the site"))
    }

    func testKeywordMustBeNear() {
        let far = "Verification" + String(repeating: " x", count: 60) + " 482913"
        XCTAssertNil(code(far))
    }
}
