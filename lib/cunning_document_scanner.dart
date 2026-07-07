import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

class CunningDocumentScanner {
  static const MethodChannel _channel = MethodChannel(
    'cunning_document_scanner',
  );

  /// Call this to start get Picture workflow.
  static Future<List<String>?> getPictures({
    int noOfPages = 100,
    bool isGalleryImportAllowed = false,
  }) async {
    if (defaultTargetPlatform == TargetPlatform.iOS) {
      final PermissionStatus cameraStatus = await Permission.camera.request();
      if (cameraStatus == PermissionStatus.denied ||
          cameraStatus == PermissionStatus.permanentlyDenied) {
        throw Exception("Permission not granted");
      }
    }

    final List<dynamic>? pictures = await _channel.invokeMethod('getPictures', {
      'noOfPages': noOfPages,
      'isGalleryImportAllowed': isGalleryImportAllowed,
    });
    return pictures?.map((e) => e as String).toList();
  }
}
