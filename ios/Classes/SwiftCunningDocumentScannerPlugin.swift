import Flutter
import UIKit
import Vision
import VisionKit

@available(iOS 13.0, *)
public class SwiftCunningDocumentScannerPlugin: NSObject, FlutterPlugin, VNDocumentCameraViewControllerDelegate {
   var resultChannel: FlutterResult?
   var presentingController: VNDocumentCameraViewController?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "cunning_document_scanner", binaryMessenger: registrar.messenger())
    let instance = SwiftCunningDocumentScannerPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    if call.method == "getPictures" {
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

  public func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
      let tempDirPath = self.getDocumentsDirectory()
      let currentDateTime = Date()
      let df = DateFormatter()
      df.dateFormat = "yyyyMMdd-HHmmss"
      let formattedDate = df.string(from: currentDateTime)
      var filenames: [String] = []
      for i in 0 ..< scan.pageCount {
          let page = scan.imageOfPage(at: i)
          let url = tempDirPath.appendingPathComponent(formattedDate + "-\(i).png")
          do {
              try page.pngData()?.write(to: url)
              filenames.append(url.path)
          } catch {
              resultChannel?(FlutterError(code: "FILE_WRITE_ERROR", message: "Failed to write file: \(error.localizedDescription)", details: nil))
              presentingController?.dismiss(animated: true)
              return
          }
      }
      resultChannel?(filenames)
      resultChannel = nil
      presentingController?.dismiss(animated: true)
      presentingController = nil
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
