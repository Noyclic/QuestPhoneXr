# Sandbox 3D browser and hand keyboard

The Sandbox browser is an Android WebView hosted inside `SandboxActivity`, captured into a GLES texture, and drawn as a world-locked stereo panel. It does not launch a separate browser Activity and does not leave the Sandbox scene.

## Open and use

1. In Sandbox, choose **Открыть 3D-браузер + клавиатуру**. The settings panel is hidden to leave the virtual page unobstructed.
2. Aim the fingertip cursor at the address field and pinch to focus it.
3. Use the virtual QWERTY keyboard to enter a URL or search phrase. **123** switches to symbols/numbers, **⇧** toggles uppercase, **⌫** deletes, **пробел** inserts a space, and **↵ / GO** navigates.
4. On a web page, pinch a text field and type with the same keyboard. Input is sent to the focused DOM element; the normal Android soft keyboard is suppressed.
5. Aim at a link or button and pinch/release to click. Hold the pinch and move the hand to drag/scroll. The colored dot is the hand's hover point on the browser panel.
6. Use the **×** in the top-right corner to close the panel. It stays subtly visible at rest, brightens when either tracked hand approaches the browser, and turns red when the fingertip cursor hovers over it. Pinch/release on the corner to close; no extra on-screen button is needed. The Sandbox object-grab interaction is restored after closing.

Only one hand owns a browser pointer at a time. Pinch is the browser click/drag gesture; natural power grip is not converted into a browser click. While the browser is open, Sandbox cube grabbing and fingertip physics contacts are temporarily paused so the same pinch cannot accidentally grab scene objects.

## Rendering and compatibility

The browser view is captured to a 960×640 bitmap and refreshed periodically, then uploaded to a GLES texture for both eyes. Pointer events are delivered at hand-tracking/render cadence, but page pixels update at a lower rate to limit CPU/GPU and memory pressure on the phone. Consequently, scrolling, animated pages, video, WebGL, DRM playback, and pages that require hardware-only WebView composition may update slowly or fail to appear in the captured image. This is not yet a full-rate native SurfaceTexture browser.

JavaScript and DOM storage are enabled for modern pages. Navigation is restricted to HTTP and HTTPS. File and content URL access are disabled, and mixed content is blocked. This browser does not claim full desktop-browser compatibility.

The hand cursor is derived from a monocular camera estimate. It is a practical pointer for the Sandbox panel, not a metric 6DoF ray or room-scale controller.
