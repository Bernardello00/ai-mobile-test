# Visual Video Scout 0.2.0

Android visual crawler/search prototype.

## v0.2
- MobileCLIP S0 INT8 fully on-device via ONNX Runtime Android.
- No API key required for visual semantic search.
- WebView rendered-card capture with PixelCopy after simulated hover, including transient previews.
- Multipage crawling: scroll-to-bottom, Next/Avanti/Successiva/rel=next and numeric pagination discovery.
- Configurable Max pages and Cards/page budgets.
- Visited URL + visual fingerprint anti-loop fencing.
- Query ranking by cosine similarity between text and rendered visual embeddings.

The build workflow downloads pinned Xenova/mobileclip_s0 ONNX assets, verifies their SHA-256 digests, validates the ONNX graph contract, then packages them inside the APK.

This is a prototype/debug build. Site-specific behavior can vary with DOM structure, authentication, anti-bot controls, DRM and terms of service.
