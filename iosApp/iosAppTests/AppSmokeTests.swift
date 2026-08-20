import XCTest
import ComposeApp

final class AppSmokeTests: XCTestCase {
    func testRootComposeControllerCanBeCreated() {
        XCTAssertNotNil(MainViewControllerKt.MainViewController())
    }
}
