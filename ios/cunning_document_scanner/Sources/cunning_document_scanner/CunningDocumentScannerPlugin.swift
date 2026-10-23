import Flutter
import UIKit
import Vision
import VisionKit

@available(iOS 13.0, *)
public class CunningDocumentScannerPlugin: NSObject, FlutterPlugin,
    VNDocumentCameraViewControllerDelegate {
    var resultChannel: FlutterResult?
    var presentingController: VNDocumentCameraViewController?
    var maxPageCount: Int = 100
    private var isFinishing = false
    private var activeScanID: UUID?
    let outputMaxDimension: CGFloat = 2048
    let outputJpegQuality: CGFloat = 0.82
    let outputQueue = DispatchQueue(
        label: "biz.cunning.cunning_document_scanner.output",
        qos: .userInitiated
    )

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: "cunning_document_scanner",
            binaryMessenger: registrar.messenger()
        )
        let instance = CunningDocumentScannerPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard call.method == "getPictures" else {
            result(FlutterMethodNotImplemented)
            return
        }

        guard resultChannel == nil else {
            result(FlutterError(
                code: "ALREADY_ACTIVE",
                message: "A document scan is already in progress",
                details: nil
            ))
            return
        }

        let arguments = call.arguments as? [String: Any]
        let rawPageCount = arguments?["noOfPages"]
        let requestedPageCount = rawPageCount as? Int
            ?? (rawPageCount as? NSNumber)?.intValue
            ?? 100
        guard requestedPageCount > 0 else {
            result(FlutterError(code: "INVALID_ARGUMENT", message: "noOfPages must be positive", details: nil))
            return
        }

        guard let presentedVC = rootViewController,
              presentedVC.viewIfLoaded?.window != nil,
              !presentedVC.isBeingDismissed,
              !presentedVC.isBeingPresented else {
            result(FlutterError(
                code: "NO_VIEW_CONTROLLER",
                message: "No view controller available to present the document camera",
                details: nil
            ))
            return
        }
        guard VNDocumentCameraViewController.isSupported else {
            result(FlutterError(
                code: "UNAVAILABLE",
                message: "Document camera is not available on this device",
                details: nil
            ))
            return
        }

        let controller = VNDocumentCameraViewController()
        controller.delegate = self
        maxPageCount = requestedPageCount
        resultChannel = result
        activeScanID = UUID()
        presentingController = controller
        presentedVC.present(controller, animated: true)
    }

    var rootViewController: UIViewController? {
        let keyWindow = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        var controller = keyWindow?.rootViewController
        while let presented = controller?.presentedViewController {
            controller = presented
        }
        return controller
    }

    func getDocumentsDirectory() -> URL {
        FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
    }

    func boundedImage(_ image: UIImage) -> UIImage {
        let width = image.size.width * image.scale
        let height = image.size.height * image.scale
        let longestSide = max(width, height)
        if longestSide <= outputMaxDimension {
            return image
        }

        let scale = outputMaxDimension / longestSide
        let targetSize = CGSize(width: width * scale, height: height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }

    // Keep the callback active through dismissal and export without retaining the dismissed
    // scanner UI. Request identity prevents a late completion from finishing a later scan.
    private func complete(_ value: Any?, scanID: UUID) {
        guard activeScanID == scanID, let result = resultChannel else { return }
        resultChannel = nil
        activeScanID = nil
        presentingController = nil
        isFinishing = false
        result(value)
    }

    private func dismissAndComplete(_ value: Any?, controller: VNDocumentCameraViewController) {
        guard let scanID = activeScanID else { return }
        isFinishing = true
        controller.dismiss(animated: true) {
            self.complete(value, scanID: scanID)
        }
    }

    public func documentCameraViewController(
        _ controller: VNDocumentCameraViewController,
        didFinishWith scan: VNDocumentCameraScan
    ) {
        guard presentingController === controller, resultChannel != nil, !isFinishing,
              let scanID = activeScanID else { return }
        isFinishing = true
        let directory = getDocumentsDirectory()
        let pageCount = min(scan.pageCount, maxPageCount)

        controller.dismiss(animated: true) {
            self.presentingController = nil
            self.outputQueue.async { [self] in
                var filenames: [String] = []
                var createdURLs: [URL] = []
                var flutterError: FlutterError?
                if pageCount == 0 {
                    flutterError = FlutterError(code: "ERROR", message: "No scanned images returned", details: nil)
                }
                for i in 0 ..< pageCount {
                    autoreleasepool {
                        let page = boundedImage(scan.imageOfPage(at: i))
                        let url = directory.appendingPathComponent("\(scanID.uuidString)-\(i).jpg")
                        guard let imageData = page.jpegData(compressionQuality: outputJpegQuality) else {
                            flutterError = FlutterError(
                                code: "IMAGE_ENCODING_ERROR",
                                message: "Failed to encode image",
                                details: nil
                            )
                            return
                        }
                        // Include the current target so a failed write cannot leave a partial file.
                        createdURLs.append(url)
                        do {
                            try imageData.write(to: url, options: .atomic)
                            filenames.append(url.path)
                        } catch {
                            flutterError = FlutterError(
                                code: "FILE_WRITE_ERROR",
                                message: "Failed to write file: \(error.localizedDescription)",
                                details: nil
                            )
                        }
                    }
                    if flutterError != nil { break }
                }
                if flutterError != nil {
                    for url in createdURLs {
                        try? FileManager.default.removeItem(at: url)
                    }
                }
                DispatchQueue.main.async {
                    if let flutterError = flutterError {
                        self.complete(flutterError, scanID: scanID)
                    } else {
                        self.complete(filenames, scanID: scanID)
                    }
                }
            }
        }
    }

    public func documentCameraViewControllerDidCancel(
        _ controller: VNDocumentCameraViewController
    ) {
        guard presentingController === controller, resultChannel != nil, !isFinishing else { return }
        dismissAndComplete(nil, controller: controller)
    }

    public func documentCameraViewController(
        _ controller: VNDocumentCameraViewController,
        didFailWithError error: Error
    ) {
        guard presentingController === controller, resultChannel != nil, !isFinishing else { return }
        dismissAndComplete(FlutterError(
            code: "ERROR",
            message: error.localizedDescription,
            details: nil
        ), controller: controller)
    }
}
