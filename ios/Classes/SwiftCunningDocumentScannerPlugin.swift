import Flutter
import UIKit
import Vision
import VisionKit

@available(iOS 13.0, *)
public class SwiftCunningDocumentScannerPlugin: NSObject, FlutterPlugin, VNDocumentCameraViewControllerDelegate {
   var resultChannel: FlutterResult?
   var presentingController: VNDocumentCameraViewController?
   var maxPageCount: Int = 100
   let outputMaxDimension: CGFloat = 2048
   let outputJpegQuality: CGFloat = 0.82
   let outputQueue = DispatchQueue(
      label: "biz.cunning.cunning_document_scanner.output",
      qos: .userInitiated
   )

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "cunning_document_scanner", binaryMessenger: registrar.messenger())
    let instance = SwiftCunningDocumentScannerPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    if call.method == "getPictures" {
        let arguments = call.arguments as? [String: Any]
        let rawPageCount = arguments?["noOfPages"]
        let requestedPageCount = rawPageCount as? Int ?? (rawPageCount as? NSNumber)?.intValue ?? 100
        self.maxPageCount = max(1, requestedPageCount)
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let presentedVC = windowScene.windows.first?.rootViewController else {
            result(FlutterError(code: "NO_VIEW_CONTROLLER", message: "No root view controller available", details: nil))
            return
        }
        self.resultChannel = result
        if VNDocumentCameraViewController.isSupported {
            self.presentingController = VNDocumentCameraViewController()
            if let presentingController = self.presentingController {
                presentingController.delegate = self
                presentedVC.present(presentingController, animated: true)
            } else {
                result(FlutterError(code: "ERROR", message: "Failed to initialize document camera", details: nil))
            }
        } else {
            result(FlutterError(code: "UNAVAILABLE", message: "Document camera is not available on this device", details: nil))
        }
    } else {
        result(FlutterMethodNotImplemented)
    }
  }

  func getDocumentsDirectory() -> URL {
      let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
      return paths[0]
  }

  func boundedImage(_ image: UIImage) -> UIImage {
      let width = image.size.width
      let height = image.size.height
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

  public func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
      let tempDirPath = self.getDocumentsDirectory()
      let currentDateTime = Date()
      let df = DateFormatter()
      df.dateFormat = "yyyyMMdd-HHmmss"
      let formattedDate = df.string(from: currentDateTime)
      let pageCount = min(scan.pageCount, maxPageCount)
      let result = resultChannel
      let controllerToDismiss = presentingController ?? controller
      resultChannel = nil
      presentingController = nil
      controllerToDismiss.dismiss(animated: true)

      outputQueue.async { [self] in
          var filenames: [String] = []
          var flutterError: FlutterError?
          for i in 0 ..< pageCount {
              autoreleasepool {
                  let page = boundedImage(scan.imageOfPage(at: i))
                  let url = tempDirPath.appendingPathComponent(
                      formattedDate + "-\(i).jpg"
                  )
                  guard let imageData = page.jpegData(
                      compressionQuality: outputJpegQuality
                  ) else {
                      flutterError = FlutterError(
                          code: "IMAGE_ENCODING_ERROR",
                          message: "Failed to encode image",
                          details: nil
                      )
                      return
                  }
                  do {
                      try imageData.write(to: url)
                      filenames.append(url.path)
                  } catch {
                      flutterError = FlutterError(
                          code: "FILE_WRITE_ERROR",
                          message: "Failed to write file: \(error.localizedDescription)",
                          details: nil
                      )
                  }
              }
              if flutterError != nil {
                  break
              }
          }

          DispatchQueue.main.async {
              if let flutterError = flutterError {
                  result?(flutterError)
              } else {
                  result?(filenames)
              }
          }
      }
  }

  public func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
      resultChannel?(nil)
      resultChannel = nil
      presentingController?.dismiss(animated: true)
      presentingController = nil
  }

  public func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
      resultChannel?(FlutterError(code: "ERROR", message: error.localizedDescription, details: nil))
      resultChannel = nil
      presentingController?.dismiss(animated: true)
      presentingController = nil
  }
}
