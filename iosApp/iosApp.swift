import ComposeApp
import SwiftUI

@main
struct MobileBankIosApp: App {
    private let appBridge = IosAppBridge()

    var body: some Scene {
        WindowGroup {
            ContentView(appBridge: appBridge)
                .onOpenURL { url in
                    appBridge.openUrl(rawUrl: url.absoluteString)
                }
        }
    }
}
