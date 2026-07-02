---
name: android-windowmanager-gestures
description: Best practices for implementing a floating/overlay keyboard layout using WindowManager, custom gesture detection, swipe-to-type, key-drag events, and handling multi-touch inputs inside custom KeyboardViews.
---

# Android WindowManager & Touch Gesture Skill

This skill details how to manage floating keyboard overlays using Android's `WindowManager` and capture custom gesture actions (like swiping, sliding, and resizing).

## 1. Floating Window Implementation (`WindowManager`)

To display a floating keyboard outside the standard IME input view overlay (or as a resizable/draggable widget), we use the Android [WindowManager](https://developer.android.com/reference/android/view/WindowManager).

### System Window Permission
Requires `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />` if shown globally, but for IME custom overlays, standard IME window tokens can be leveraged.

### WindowManager Layout Parameters
```kotlin
val layoutParams = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    },
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = 100 // initial position
    y = 100
}
```

## 2. Touch Gesture Detection

To support gesture typing (swipe-to-type) or sliding actions (e.g. Spacebar cursor control), we must intercept and process multi-touch events in a custom `KeyboardView`.

### Custom Spacebar Slider Gesture
Allows moving the text cursor left or right by dragging on the spacebar:
```kotlin
class SpacebarGestureDetector(private val onSwipe: (dx: Float) -> Unit) {
    private var startX = 0f
    private val swipeThreshold = 20f // pixels

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                if (Math.abs(dx) > swipeThreshold) {
                    onSwipe(dx)
                    startX = event.x // Reset threshold base
                }
            }
        }
        return true
    }
}
```

### Drag to Move Floating Keyboard
```kotlin
keyboardView.setOnTouchListener(object : View.OnTouchListener {
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(floatingView, layoutParams)
                return true
            }
        }
        return false
    }
})
```

## 3. Gestures & Swipe-to-Type Patterns
*   **Sampling Input Points**: Collect raw coordinate points `(x, y, timestamp)` in a buffer on `MotionEvent.ACTION_DOWN` / `ACTION_MOVE`.
*   **Path Matching**: Match the recorded gesture path against candidate words using an algorithms like dynamic time warping (DTW) or distance vectors relative to keyboard keys coordinates.
*   **Drawing Path Overlays**: Render a smooth trail line on screen as the user swipes. Draw inside the custom `KeyboardView.onDraw()` utilizing a canvas and custom `Paint`.
