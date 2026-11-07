package com.noyclic.questphonexr.sandbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.noyclic.questphonexr.tracking.HandSide
import kotlin.math.max

/**
 * Real Android WebView + hand-operated keyboard, captured as a texture and
 * displayed as a world-locked stereo panel by SandboxRenderer.
 *
 * The view stays attached behind the GLSurfaceView so WebView can maintain its
 * normal lifecycle; it is never used as a phone-screen dialog.
 */
internal class SandboxBrowserPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val onCloseRequested: () -> Unit = {}
) : LinearLayout(context, attrs) {

    private val addressField = EditText(context)
    private val webView = KeyboardControlledWebView(context)
    private val keyboard = SandboxVirtualKeyboardView(context, ::dispatchVirtualKey)

    private var activePointerSide: HandSide? = null
    private var activePointerDownTime = 0L
    private var lastPointer = SandboxBrowserInteraction.PixelPoint(0f, 0f)
    private var lastPointerEventMs = 0L

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(12, 19, 29))
        isClickable = true
        buildToolbar()
        buildWebView()
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, 390)
        )
        addView(
            keyboard,
            LayoutParams(LayoutParams.MATCH_PARENT, 190)
        )
    }

    private fun buildToolbar() {
        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 5, 8, 5)
            setBackgroundColor(Color.rgb(24, 36, 51))
        }
        toolbar.addView(toolbarButton("‹") { if (webView.canGoBack()) webView.goBack() },
            LayoutParams(54, LayoutParams.MATCH_PARENT))
        toolbar.addView(toolbarButton("›") { if (webView.canGoForward()) webView.goForward() },
            LayoutParams(54, LayoutParams.MATCH_PARENT))
        toolbar.addView(toolbarButton("↻") { webView.reload() },
            LayoutParams(54, LayoutParams.MATCH_PARENT))

        addressField.apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.rgb(20, 28, 38))
            setHintTextColor(Color.DKGRAY)
            hint = "Адрес сайта или поисковый запрос"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(12, 0, 12, 0)
            setBackgroundColor(Color.WHITE)
            showSoftInputOnFocus = false
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                ) {
                    navigate()
                    true
                } else {
                    false
                }
            }
        }
        toolbar.addView(
            addressField,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(4, 0, 8, 0)
            }
        )
        toolbar.addView(
            toolbarButton("GO") { navigate() },
            LayoutParams(82, LayoutParams.MATCH_PARENT)
        )
        toolbar.addView(
            toolbarButton("×") { onCloseRequested() },
            LayoutParams(54, LayoutParams.MATCH_PARENT)
        )
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, 60))
    }

    private fun toolbarButton(label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(43, 66, 91))
            setPadding(2, 0, 2, 0)
            minWidth = 0
            minHeight = 0
            setOnClickListener { action() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!addressField.hasFocus() && !url.isNullOrBlank()) {
                    addressField.setText(url)
                    addressField.setSelection(addressField.text.length)
                }
            }
        }
        webView.loadUrl(DEFAULT_HOME)
    }

    private fun dispatchVirtualKey(key: String) {
        if (addressField.hasFocus()) {
            when (key) {
                SandboxVirtualKeyboardView.KEY_BACKSPACE -> {
                    val text = addressField.text
                    val start = addressField.selectionStart.coerceAtLeast(0)
                    val end = addressField.selectionEnd.coerceAtLeast(0)
                    val deleteStart = if (start == end) (start - 1).coerceAtLeast(0) else minOf(start, end)
                    val deleteEnd = maxOf(start, end)
                    if (deleteEnd > deleteStart) text.delete(deleteStart, deleteEnd)
                }
                SandboxVirtualKeyboardView.KEY_SPACE -> addressField.text.insert(
                    addressField.selectionStart.coerceAtLeast(0), " "
                )
                SandboxVirtualKeyboardView.KEY_ENTER -> navigate()
                else -> addressField.text.insert(
                    addressField.selectionStart.coerceAtLeast(0), key
                )
            }
            return
        }

        val command = when (key) {
            SandboxVirtualKeyboardView.KEY_BACKSPACE -> "backspace"
            SandboxVirtualKeyboardView.KEY_ENTER -> "enter"
            else -> "insert"
        }
        val value = when (key) {
            SandboxVirtualKeyboardView.KEY_BACKSPACE,
            SandboxVirtualKeyboardView.KEY_ENTER -> ""
            SandboxVirtualKeyboardView.KEY_SPACE -> " "
            else -> key
        }
        val script = """
            (function(command, value) {
              var element = document.activeElement;
              if (!element) return false;
              var tag = (element.tagName || '').toUpperCase();
              var editable = element.isContentEditable ||
                tag === 'TEXTAREA' ||
                (tag === 'INPUT' && !/^(button|checkbox|radio|submit|reset|file|image|color|range)$/i.test(element.type || 'text'));
              if (!editable) return false;

              if (command === 'enter') {
                element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
                if (element.form && typeof element.form.requestSubmit === 'function') {
                  element.form.requestSubmit();
                } else {
                  element.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
                }
                return true;
              }

              if (element.isContentEditable) {
                var inserted = command === 'backspace'
                  ? document.execCommand('delete', false)
                  : document.execCommand('insertText', false, value);
                if (inserted) return true;
              }

              if (typeof element.setRangeText !== 'function') return false;
              var start = 0;
              var end = 0;
              try {
                start = element.selectionStart;
                end = element.selectionEnd;
              } catch (ignored) {
                return false;
              }
              if (start == null || end == null) return false;
              if (command === 'backspace' && start === end) start = Math.max(0, start - 1);
              var insertedText = command === 'backspace' ? '' : value;
              var changed = end > start || insertedText.length > 0;
              if (!changed) return true;
              element.setRangeText(insertedText, start, end, 'end');
              element.dispatchEvent(new InputEvent('input', {
                bubbles: true,
                inputType: command === 'backspace' ? 'deleteContentBackward' : 'insertText',
                data: command === 'backspace' ? null : value
              }));
              return true;
            })(${org.json.JSONObject.quote(command)}, ${org.json.JSONObject.quote(value)})
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            if (result != "true" && !isDestroyed && !isFinishing) {
                addressField.requestFocus()
                if (key == SandboxVirtualKeyboardView.KEY_ENTER) {
                    navigate()
                } else {
                    val text = when (key) {
                        SandboxVirtualKeyboardView.KEY_BACKSPACE -> ""
                        SandboxVirtualKeyboardView.KEY_SPACE -> " "
                        else -> key
                    }
                    if (key == SandboxVirtualKeyboardView.KEY_BACKSPACE) {
                        val end = addressField.selectionEnd.coerceAtLeast(0)
                        if (end > 0) addressField.text.delete(end - 1, end)
                    } else {
                        addressField.text.insert(addressField.selectionStart.coerceAtLeast(0), text)
                    }
                }
            }
        }
    }

    private fun navigate() {
        val value = addressField.text.toString().trim()
        if (value.isEmpty()) return
        val target = when {
            value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("http://", ignoreCase = true) -> value
            value.contains(' ') || !value.contains('.') ->
                "https://www.google.com/search?q=" + Uri.encode(value)
            else -> "https://$value"
        }
        addressField.clearFocus()
        webView.loadUrl(target)
    }

    /** Draws the native WebView and hand keyboard into the GLES texture. */
    fun captureTextureBitmap(): Bitmap {
        val width = SandboxBrowserInteraction.TEXTURE_WIDTH
        val height = SandboxBrowserInteraction.TEXTURE_HEIGHT
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(12, 19, 29))
        draw(canvas)
        return bitmap
    }

    /**
     * Convert a camera pinch into Android down/move/up events targeted at the
     * WebView or virtual keyboard. Only one hand owns the pointer at a time.
     */
    fun updateHandInput(snapshot: SandboxSceneSnapshot) {
        val activeSide = activePointerSide
        if (activeSide != null) {
            val hand = if (activeSide == HandSide.LEFT) snapshot.leftHand else snapshot.rightHand
            if (!hand.cameraTracked || !hand.pinching) {
                // Clear ownership before dispatch: a toolbar click may close the
                // browser synchronously and call releaseHandPointer() again.
                val downTime = activePointerDownTime
                val releasePoint = lastPointer
                activePointerSide = null
                lastPointerEventMs = 0L
                dispatchHandEvent(
                    MotionEvent.ACTION_UP,
                    releasePoint,
                    downTime,
                    SystemClock.uptimeMillis()
                )
                return
            }
            val point = SandboxBrowserInteraction.map(hand.cursor, clampToPanel = true)
                ?: lastPointer
            val now = SystemClock.uptimeMillis()
            if (kotlin.math.abs(point.x - lastPointer.x) >= 1.5f ||
                kotlin.math.abs(point.y - lastPointer.y) >= 1.5f ||
                now - lastPointerEventMs >= POINTER_KEEPALIVE_MS
            ) {
                dispatchHandEvent(MotionEvent.ACTION_MOVE, point, activePointerDownTime, now)
                lastPointer = point
                lastPointerEventMs = now
            }
            return
        }

        val candidate = listOf(snapshot.leftHand, snapshot.rightHand)
            .firstNotNullOfOrNull { hand ->
                if (!hand.cameraTracked || !hand.pinching) null
                else SandboxBrowserInteraction.map(hand.cursor)?.let { hand.hand to it }
            } ?: return

        activePointerSide = candidate.first
        activePointerDownTime = SystemClock.uptimeMillis()
        lastPointerEventMs = activePointerDownTime
        lastPointer = candidate.second
        dispatchHandEvent(
            MotionEvent.ACTION_DOWN,
            candidate.second,
            activePointerDownTime,
            activePointerDownTime
        )
    }

    fun releaseHandPointer() {
        val side = activePointerSide ?: return
        activePointerSide = null
        val now = SystemClock.uptimeMillis()
        dispatchHandEvent(MotionEvent.ACTION_UP, lastPointer, activePointerDownTime, now)
        lastPointerEventMs = 0L
    }

    private fun dispatchHandEvent(
        action: Int,
        point: SandboxBrowserInteraction.PixelPoint,
        downTime: Long,
        eventTime: Long
    ) {
        val event = MotionEvent.obtain(
            downTime,
            eventTime.coerceAtLeast(downTime),
            action,
            point.x,
            point.y,
            0
        )
        dispatchTouchEvent(event)
        event.recycle()
    }

    fun focusAddressBar() {
        webView.url?.takeIf { it.isNotBlank() }?.let { addressField.setText(it) }
        addressField.requestFocus()
        addressField.setSelection(addressField.text.length)
    }

    fun onResumeBrowser() {
        webView.onResume()
    }

    fun onPauseBrowser() {
        releaseHandPointer()
        webView.onPause()
    }

    fun destroyBrowser() {
        releaseHandPointer()
        webView.stopLoading()
        webView.destroy()
    }

    private companion object {
        const val DEFAULT_HOME = "https://www.google.com"
        const val POINTER_KEEPALIVE_MS = 80L
    }
}

private class KeyboardControlledWebView(context: Context) : WebView(context) {
    // Web text fields remain focusable to the page, but Android's phone IME stays
    // suppressed. Characters are inserted by the in-world keyboard through DOM.
    override fun onCheckIsTextEditor(): Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null
}

internal class SandboxVirtualKeyboardView(
    context: Context,
    private val onKey: (String) -> Unit
) : View(context) {
    private data class Key(val label: String, val weight: Float = 1f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressedKey: String? = null
    private var shifted = false
    private var numeric = false

    private val alphaRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map { Key(it) },
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map { Key(it) },
        listOf(Key("⇧", 1.25f)) +
            listOf("z", "x", "c", "v", "b", "n", "m").map { Key(it) } +
            listOf(Key("⌫", 1.25f)),
        listOf(
            Key("123", 1.2f), Key("-", 0.8f), Key("_", 0.8f), Key(".", 0.8f),
            Key("/", 0.8f), Key("@", 0.8f), Key("SPACE", 2.2f), Key("↵", 1.2f)
        )
    )

    private val numericRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { Key(it) },
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")").map { Key(it) },
        listOf(Key("⇧", 1.25f)) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(it) } +
            listOf(Key("⌫", 1.25f)),
        listOf(
            Key("ABC", 1.2f), Key(",", 0.8f), Key(".", 0.8f), Key("/", 0.8f),
            Key("=", 0.8f), Key("@", 0.8f), Key("SPACE", 2.2f), Key("↵", 1.2f)
        )
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 27, 39))
        val rows = if (numeric) numericRows else alphaRows
        val rowHeight = height.toFloat() / rows.size
        rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val gap = 5f
            val availableWidth = width - gap * (row.size + 1)
            var left = gap
            val top = rowIndex * rowHeight + 4f
            val bottom = (rowIndex + 1) * rowHeight - 4f
            row.forEach { key ->
                val keyWidth = availableWidth * key.weight / totalWeight
                val right = left + keyWidth
                val label = when {
                    key.label.length == 1 && key.label[0].isLetter() && shifted ->
                        key.label.uppercase()
                    else -> key.label
                }
                paint.color = if (pressedKey == key.label) {
                    Color.rgb(53, 112, 160)
                } else {
                    when (key.label) {
                        "GO", "↵" -> Color.rgb(44, 122, 105)
                        "⌫", "⇧", "123", "ABC" -> Color.rgb(54, 72, 93)
                        else -> Color.rgb(38, 52, 70)
                    }
                }
                canvas.drawRoundRect(RectF(left, top, right, bottom), 8f, 8f, paint)
                paint.color = Color.WHITE
                paint.textSize = if (key.label == "SPACE") 17f else 21f
                paint.textAlign = Paint.Align.CENTER
                val baseline = (top + bottom) * 0.5f -
                    (paint.ascent() + paint.descent()) * 0.5f
                canvas.drawText(
                    if (key.label == "SPACE") "пробел" else label,
                    (left + right) * 0.5f,
                    baseline,
                    paint
                )
                left = right + gap
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = keyAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val released = keyAt(event.x, event.y)
                if (released != null && released == pressedKey) activate(released)
                pressedKey = null
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun keyAt(x: Float, y: Float): String? {
        if (height <= 0 || width <= 0 || x < 0f || x >= width || y < 0f || y >= height) return null
        val rows = if (numeric) numericRows else alphaRows
        val row = rows[(y / (height.toFloat() / rows.size)).toInt().coerceIn(rows.indices)]
        val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
        val gap = 5f
        val availableWidth = width - gap * (row.size + 1)
        var left = gap
        for (key in row) {
            val right = left + availableWidth * key.weight / totalWeight
            if (x in left..right) return key.label
            left = right + gap
        }
        return null
    }

    private fun activate(label: String) {
        when (label) {
            "⇧" -> shifted = !shifted
            "⌫" -> onKey(KEY_BACKSPACE)
            "SPACE" -> onKey(KEY_SPACE)
            "↵" -> onKey(KEY_ENTER)
            "123" -> numeric = true
            "ABC" -> numeric = false
            else -> {
                onKey(if (shifted && label.length == 1 && label[0].isLetter()) {
                    label.uppercase()
                } else label)
                shifted = false
            }
        }
        invalidate()
    }

    companion object {
        const val KEY_BACKSPACE = "<BACKSPACE>"
        const val KEY_SPACE = "<SPACE>"
        const val KEY_ENTER = "<ENTER>"
    }
}
