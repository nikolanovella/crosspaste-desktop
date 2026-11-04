import SwiftUI
import WebKit
import UIKit

@main
struct CrossPasteIOSApp: App {
    var body: some Scene {
        WindowGroup {
            CrossPasteWebApp()
        }
    }
}

private struct CrossPasteWebApp: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let contentController = WKUserContentController()
        contentController.addScriptMessageHandler(context.coordinator, contentWorld: .page, name: "clipboard")
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        let assetHandler = BundleAssetSchemeHandler()
        configuration.setURLSchemeHandler(assetHandler, forURLScheme: "crosspaste")
        let webView = WKWebView(frame: .zero, configuration: configuration)
        context.coordinator.assetHandler = assetHandler
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.load(URLRequest(url: URL(string: "crosspaste://app/index.html")!))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKScriptMessageHandlerWithReply {
        var assetHandler: BundleAssetSchemeHandler?

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage,
            replyHandler: @escaping (Any?, String?) -> Void
        ) {
            guard let body = message.body as? [String: Any], let action = body["action"] as? String else {
                replyHandler(nil, "Invalid clipboard request")
                return
            }
            switch action {
            case "read": replyHandler(UIPasteboard.general.string ?? "", nil)
            case "write":
                guard let text = body["text"] as? String else {
                    replyHandler(nil, "Missing clipboard text")
                    return
                }
                UIPasteboard.general.string = text
                replyHandler(true, nil)
            default: replyHandler(nil, "Unsupported clipboard action")
            }
        }
    }
}

private final class BundleAssetSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url, url.host == "app" else {
            urlSchemeTask.didFailWithError(URLError(.badURL))
            return
        }
        let relativePath = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let requestedPath = relativePath.isEmpty ? "index.html" : relativePath
        guard !requestedPath.split(separator: "/").contains(".."),
              let root = Bundle.main.resourceURL?.appendingPathComponent("ios-dist", isDirectory: true) else {
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let fileURL = root.appendingPathComponent(requestedPath).standardizedFileURL
        guard fileURL.path.hasPrefix(root.standardizedFileURL.path + "/"),
              let data = try? Data(contentsOf: fileURL) else {
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let mimeType: String
        switch fileURL.pathExtension.lowercased() {
        case "html": mimeType = "text/html"
        case "js": mimeType = "text/javascript"
        case "css": mimeType = "text/css"
        case "json": mimeType = "application/json"
        default: mimeType = "application/octet-stream"
        }
        let response = URLResponse(url: url, mimeType: mimeType, expectedContentLength: data.count, textEncodingName: "utf-8")
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}
}
