/// Data models exchanged between the Dart API and the native FaceVault plugin.
library facevault.models;

import 'package:flutter/foundation.dart';

/// The face-embedding model architecture to load natively.
enum ModelType {
  /// MobileFaceNet — lightweight, 128-dimensional embeddings (default).
  mobilefacenet,

  /// ArcFace — heavier, higher-accuracy 512-dimensional embeddings.
  arcface;

  /// The wire value sent to the platform channel.
  String get wireName => name.toUpperCase();
}

/// Tunable configuration passed to [FaceVault.init].
@immutable
class FaceVaultConfig {
  /// Minimum cosine similarity (0..1) for a positive match.
  final double matchThreshold;

  /// Which embedding model to load.
  final ModelType modelType;

  /// Whether live capture enforces liveness checks (reserved for camera flows).
  final bool enableAntiSpoofing;

  /// Number of poses to capture during guided enrollment (reserved).
  final int maxEnrollmentAngles;

  /// Explicit SQLCipher passphrase, or null to auto-generate one in the Keystore.
  final String? dbPassphrase;

  /// Creates a configuration; all fields have sensible defaults.
  const FaceVaultConfig({
    this.matchThreshold = 0.60,
    this.modelType = ModelType.mobilefacenet,
    this.enableAntiSpoofing = true,
    this.maxEnrollmentAngles = 5,
    this.dbPassphrase,
  });

  /// Serializes this config for the platform channel.
  Map<String, dynamic> toMap() => <String, dynamic>{
        'matchThreshold': matchThreshold,
        'modelType': modelType.wireName,
        'enableAntiSpoofing': enableAntiSpoofing,
        'maxEnrollmentAngles': maxEnrollmentAngles,
        'dbPassphrase': dbPassphrase,
      };
}

/// An enrolled identity, as returned by the native store.
///
/// The raw embedding vectors stay on the native side; [embeddingCount] reports
/// how many were stored.
@immutable
class PersonRecord {
  /// Stable UUID string.
  final String personId;

  /// Display name.
  final String name;

  /// Arbitrary labels.
  final List<String> tags;

  /// How many embedding vectors are stored for this person.
  final int embeddingCount;

  /// Optional thumbnail URI string.
  final String? thumbnailUri;

  /// Enrollment time, epoch millis.
  final int createdAt;

  /// Last modification time, epoch millis.
  final int updatedAt;

  /// Creates a person record.
  const PersonRecord({
    required this.personId,
    required this.name,
    required this.tags,
    required this.embeddingCount,
    required this.thumbnailUri,
    required this.createdAt,
    required this.updatedAt,
  });

  /// Builds a record from a platform-channel map.
  factory PersonRecord.fromMap(Map<dynamic, dynamic> map) => PersonRecord(
        personId: map['personId'] as String,
        name: (map['name'] as String?) ?? '',
        tags: (map['tags'] as List<dynamic>? ?? <dynamic>[])
            .map((dynamic e) => e as String)
            .toList(),
        embeddingCount: (map['embeddingCount'] as int?) ?? 0,
        thumbnailUri: map['thumbnailUri'] as String?,
        createdAt: (map['createdAt'] as int?) ?? 0,
        updatedAt: (map['updatedAt'] as int?) ?? 0,
      );

  @override
  String toString() => 'PersonRecord($personId, $name, tags=$tags)';
}

/// An axis-aligned face box in source-image pixel coordinates.
@immutable
class FaceBounds {
  /// Left edge in pixels.
  final double left;

  /// Top edge in pixels.
  final double top;

  /// Right edge in pixels.
  final double right;

  /// Bottom edge in pixels.
  final double bottom;

  /// Creates face bounds.
  const FaceBounds(this.left, this.top, this.right, this.bottom);

  /// Builds bounds from a `[left, top, right, bottom]` list.
  factory FaceBounds.fromList(List<dynamic> v) => FaceBounds(
        (v[0] as num).toDouble(),
        (v[1] as num).toDouble(),
        (v[2] as num).toDouble(),
        (v[3] as num).toDouble(),
      );

  /// Box width in pixels.
  double get width => right - left;

  /// Box height in pixels.
  double get height => bottom - top;

  @override
  String toString() => 'FaceBounds($left, $top, $right, $bottom)';
}

/// The outcome of matching one query face against the gallery.
@immutable
class MatchResult {
  /// Whether the best score met the configured threshold.
  final bool matched;

  /// Cosine similarity of the best candidate, 0..1.
  final double confidence;

  /// The matched person, or null when no candidate cleared the threshold.
  final PersonRecord? person;

  /// Location of the query face in the source image.
  final FaceBounds faceBounds;

  /// Creates a match result.
  const MatchResult({
    required this.matched,
    required this.confidence,
    required this.person,
    required this.faceBounds,
  });

  /// Builds a result from a platform-channel map.
  factory MatchResult.fromMap(Map<dynamic, dynamic> map) => MatchResult(
        matched: (map['matched'] as bool?) ?? false,
        confidence: ((map['confidence'] as num?) ?? 0).toDouble(),
        person: map['person'] == null
            ? null
            : PersonRecord.fromMap(map['person'] as Map<dynamic, dynamic>),
        faceBounds: FaceBounds.fromList(
          (map['faceBounds'] as List<dynamic>? ?? <dynamic>[0, 0, 0, 0]),
        ),
      );

  @override
  String toString() =>
      'MatchResult(matched=$matched, confidence=$confidence, person=$person)';
}
