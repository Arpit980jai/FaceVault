# FaceVault

[![pub package](https://img.shields.io/pub/v/facevault.svg)](https://pub.dev/packages/facevault)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/minSdk-24-blue.svg)](https://developer.android.com/about/versions/nougat)

**On-device facial recognition for Flutter (Android).** Enroll people from photos
and search them back — single photo, multiple photos, all faces in a group photo,
or against a target list. Detection, embedding, encrypted storage and matching all
run **locally**; no data leaves the device and **no internet permission** is used.

```
facevault/
├── lib/        # Dart API — FaceVault + models (PersonRecord, MatchResult, ...)
├── android/    # Native engine: embedding, store, matching, liveness + MethodChannel bridge
└── example/    # Flutter example app
```

> Platform support: **Android only** (minSdk 24). iOS is not implemented.

## Install

```yaml
dependencies:
  facevault: ^0.0.1
```

or `flutter pub add facevault`.

### Bundle the embedding model

The native engine needs a TFLite face model. Drop a MobileFaceNet export at:

```
android/src/main/assets/mobilefacenet.tflite
```

It must take a `1×112×112×3` float input normalized to `[-1, 1]` and output a
`1×128` embedding (1×512 for ArcFace). See
`android/src/main/assets/README_MODEL.txt`. The model is **not** committed (size +
licensing), so add it before building. Everything compiles without it; only the
embedding step needs it at runtime.

## Usage

Photos are passed as encoded image bytes (`Uint8List` of a JPEG/PNG). Grab them
however you like — `image_picker`, `camera`, an asset, etc.

```dart
import 'package:facevault/facevault.dart';

await FaceVault.init(const FaceVaultConfig(matchThreshold: 0.60));
```

### Enroll

```dart
final PersonRecord person = await FaceVault.enrollPerson(
  name: 'Ada Lovelace',
  photos: <Uint8List>[photo1, photo2, photo3],
  tags: <String>['family'],
);
```

### The four search modes

```dart
// 1) Single photo — one best match.
final MatchResult r = await FaceVault.searchByPhoto(queryBytes);
if (r.matched) {
  print('${r.person!.name} @ ${(r.confidence * 100).toStringAsFixed(1)}%');
}

// 2) Several photos of the SAME person — aggregated (mean-pooled) match.
final MatchResult r2 = await FaceVault.searchByPhotos(<Uint8List>[a, b]);

// 3) Group photo — find ALL faces and match each independently.
final List<MatchResult> all = await FaceVault.findAllInPhoto(groupBytes);
for (final MatchResult m in all) {
  print('${m.faceBounds} -> ${m.matched ? m.person!.name : "unknown"}');
}

// 4) Group photo + target list — is each requested person present?
final Map<String, MatchResult?> hits =
    await FaceVault.findFromList(groupBytes, <String>[id1, id2]);
hits.forEach((String id, MatchResult? m) {
  if (m == null) {
    print('$id not enrolled');
  } else {
    print('$id ${m.matched ? "FOUND @ ${m.confidence}" : "not in photo"}');
  }
});
```

### Management

```dart
final List<PersonRecord> everyone = await FaceVault.listAllPersons();
await FaceVault.updatePerson(personId, <Uint8List>[freshBytes]);
await FaceVault.deletePerson(personId);
```

See [`example/`](example/lib/main.dart) for a complete app.

> **Live camera capture / guided enrollment** is best handled on the Flutter side
> (e.g. the `camera` or `image_picker` packages) — capture frames there and pass
> the bytes to FaceVault. The native engine ships the photo-based data-plane.

## Architecture

The diagrams below depict the full native engine. The plugin exposes the
photo-based data-plane (embedding, store, matching, public API); the camera /
liveness / enrollment-UI pieces shown are handled app-side in a Flutter app.

### Camera & Liveness (native engine)
![Module 1 — Camera & Liveness](https://raw.githubusercontent.com/Arpit980jai/FaceVault/main/assets/module1.png)

### Embedding pipeline
`FaceEmbedder` (TFLite + NNAPI), `FacePreprocessor` crop/align, mean-pooling to a
128-d vector.

![Module 2 — Embedding Pipeline](https://raw.githubusercontent.com/Arpit980jai/FaceVault/main/assets/module2.png)

### Encrypted face store
`PersonRecord`/`PersonDao`/`FaceDatabase`, the `EmbeddingTypeConverter`, and the
`FaceStore` repository over SQLCipher-encrypted Room.

![Module 3 — Face Store](https://raw.githubusercontent.com/Arpit980jai/FaceVault/main/assets/module3.png)

### Matching, API & UI
`EmbeddingMatcher`/`MatchResult` and the `FaceVault` engine.

![Modules 4–7 — Matching, API, UI & Sample](https://raw.githubusercontent.com/Arpit980jai/FaceVault/main/assets/module4_module5_module6.png)

## Security model

- Embeddings and metadata live in an **SQLCipher-encrypted** Room database
  (`facevault.db`, AES-256).
- The passphrase is random (256-bit), encrypted with a hardware-backed
  **AES-256-GCM** Keystore key, and stored sealed in private prefs. Plaintext only
  exists transiently in memory while opening the DB. Override via
  `FaceVaultConfig.dbPassphrase`.
- No data ever leaves the device.

## License

[MIT](LICENSE) © Arpit Jaiswal
