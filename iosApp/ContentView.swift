import ComposeApp
import SwiftUI
import UIKit

struct ContentView: UIViewControllerRepresentable {
    let appBridge: IosAppBridge

    func makeUIViewController(context: Context) -> UIViewController {
        appBridge.makeViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // Compose owns rendering and reacts to shared state.
    }
}

#Preview {
    ContentView(appBridge: IosAppBridge())
}
