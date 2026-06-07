## 0.0.1

* Initial release: Flutter plugin wrapping the on-device FaceVault Android engine.
* Dart API: `init`, `enrollPerson`, `searchByPhoto`, `searchByPhotos`,
  `findAllInPhoto`, `findFromList`, `deletePerson`, `listAllPersons`,
  `updatePerson`.
* Android (minSdk 24): ML Kit face detection, TFLite (MobileFaceNet/ArcFace)
  embeddings, SQLCipher-encrypted Room storage, cosine matching. No internet.
