FaceVault — bundled TFLite model
================================

Place the face-embedding model file in THIS directory:

    facevault-core/src/main/assets/mobilefacenet.tflite   (128-d, default)
    facevault-core/src/main/assets/arcface.tflite         (512-d, optional)

The model is intentionally NOT checked into the repository because of its size
and licensing. Any standard MobileFaceNet TFLite export works as long as it:

  * accepts a 1 x 112 x 112 x 3 float32 input,
  * is normalized to the [-1, 1] range ((pixel - 127.5) / 128), and
  * outputs a 1 x 128 float32 embedding (1 x 512 for ArcFace).

The Gradle config keeps .tflite files uncompressed (android.androidResources
noCompress += "tflite") so the interpreter can memory-map them directly.

If the file is missing, FaceEmbedder will throw when first used; everything else
(camera, liveness, store, matching) compiles and runs without it.
