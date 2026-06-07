/// FaceVault — on-device facial recognition for Flutter (Android).
///
/// All work runs locally via the native plugin: face detection, TFLite
/// embedding, encrypted (SQLCipher) storage and cosine matching. No data leaves
/// the device and no internet permission is required.
///
/// Initialize once, then enroll people from photos and search:
/// ```dart
/// await FaceVault.init();
/// await FaceVault.enrollPerson(name: 'Ada', photos: [jpgBytes]);
/// final r = await FaceVault.searchByPhoto(queryBytes);
/// if (r.matched) print('Found ${r.person!.name} @ ${r.confidence}');
/// ```
library facevault;

import 'package:flutter/services.dart';

import 'src/facevault_models.dart';

export 'src/facevault_models.dart';

/// The public, on-device facial-recognition entry point.
///
/// Photos are passed as encoded image bytes (`Uint8List` of a JPEG/PNG); the
/// native layer decodes, detects, embeds and matches them. Every method is
/// asynchronous and throws a [PlatformException] on native failure.
class FaceVault {
  FaceVault._();

  static const MethodChannel _channel = MethodChannel('facevault');

  /// Initializes FaceVault. Call once (e.g. in `main`) before anything else.
  ///
  /// Safe to call repeatedly; subsequent calls are ignored natively.
  static Future<void> init([FaceVaultConfig config = const FaceVaultConfig()]) {
    return _channel.invokeMethod<void>('init', config.toMap());
  }

  /// Enrolls a new person from one or more [photos] (encoded image bytes).
  ///
  /// Returns the persisted [PersonRecord]. Throws if no face was found.
  static Future<PersonRecord> enrollPerson({
    required String name,
    required List<Uint8List> photos,
    List<String> tags = const <String>[],
  }) async {
    final Map<dynamic, dynamic> map =
        (await _channel.invokeMethod<Map<dynamic, dynamic>>('enrollPerson', <String, dynamic>{
      'name': name,
      'photos': photos,
      'tags': tags,
    }))!;
    return PersonRecord.fromMap(map);
  }

  /// Searches a single photo and returns the single best [MatchResult].
  static Future<MatchResult> searchByPhoto(Uint8List photo) async {
    final Map<dynamic, dynamic> map =
        (await _channel.invokeMethod<Map<dynamic, dynamic>>('searchByPhoto', <String, dynamic>{
      'photo': photo,
    }))!;
    return MatchResult.fromMap(map);
  }

  /// Searches using several photos of the **same** person (mean-pooled query).
  static Future<MatchResult> searchByPhotos(List<Uint8List> photos) async {
    final Map<dynamic, dynamic> map =
        (await _channel.invokeMethod<Map<dynamic, dynamic>>('searchByPhotos', <String, dynamic>{
      'photos': photos,
    }))!;
    return MatchResult.fromMap(map);
  }

  /// Detects every face in a group [photo] and matches each independently.
  static Future<List<MatchResult>> findAllInPhoto(Uint8List photo) async {
    final List<dynamic> list =
        (await _channel.invokeMethod<List<dynamic>>('findAllInPhoto', <String, dynamic>{
      'photo': photo,
    }))!;
    return list
        .map((dynamic e) => MatchResult.fromMap(e as Map<dynamic, dynamic>))
        .toList();
  }

  /// In a group [photo], reports whether each id in [targetPersonIds] is present.
  ///
  /// The returned map value is null when that id is not enrolled.
  static Future<Map<String, MatchResult?>> findFromList(
    Uint8List photo,
    List<String> targetPersonIds,
  ) async {
    final Map<dynamic, dynamic> map =
        (await _channel.invokeMethod<Map<dynamic, dynamic>>('findFromList', <String, dynamic>{
      'photo': photo,
      'targetPersonIds': targetPersonIds,
    }))!;
    return map.map((dynamic key, dynamic value) => MapEntry<String, MatchResult?>(
          key as String,
          value == null
              ? null
              : MatchResult.fromMap(value as Map<dynamic, dynamic>),
        ));
  }

  /// Deletes the enrolled person with [personId].
  static Future<void> deletePerson(String personId) {
    return _channel.invokeMethod<void>('deletePerson', <String, dynamic>{
      'personId': personId,
    });
  }

  /// Returns every enrolled person.
  static Future<List<PersonRecord>> listAllPersons() async {
    final List<dynamic> list =
        (await _channel.invokeMethod<List<dynamic>>('listAllPersons'))!;
    return list
        .map((dynamic e) => PersonRecord.fromMap(e as Map<dynamic, dynamic>))
        .toList();
  }

  /// Re-embeds [newPhotos] and replaces the template for [personId].
  static Future<PersonRecord> updatePerson(
    String personId,
    List<Uint8List> newPhotos,
  ) async {
    final Map<dynamic, dynamic> map =
        (await _channel.invokeMethod<Map<dynamic, dynamic>>('updatePerson', <String, dynamic>{
      'personId': personId,
      'newPhotos': newPhotos,
    }))!;
    return PersonRecord.fromMap(map);
  }
}
