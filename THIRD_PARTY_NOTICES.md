# Third-party components and test media

The application uses AndroidX / Jetpack Compose and Kotlin / kotlinx.coroutines through Maven dependencies. Their upstream licenses and notices apply (principally Apache License 2.0). No third-party commercial APK or private source is included. Platform media APIs provide decoding and encoding; FFmpeg is used only to generate/validate test fixtures on the development machine and is not bundled in the APK.

The test-only public clip is **Sintel trailer**, © Blender Foundation, licensed **Creative Commons Attribution 3.0**. It is not shipped in the application APK (only local instrumentation-test assets; the delivered source archive contains a preparation script and attribution, not video files). Full source URL, license URL, attribution, and hash: [test-media/SOURCES.md](test-media/SOURCES.md).

Synthetic test media is generated for this project; it contains test patterns and tones, not user data. No real API key or user audio is bundled.
