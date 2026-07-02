import re

kt_path = r'c:\Users\satht\Documents\Flowboard-android\app\src\main\java\com\flowboard\ime\service\FlowboardIMEService.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# 1. In updateFloatingWindowMode, remove scaleX/scaleY and use native setKeyHeight
old_float_init = '''            val baseWidth = (300 * metrics.density)
            val scaledWidth = (baseWidth * savedScale).toInt()
            
            lp.width = scaledWidth
            // Default to wrap_content initially, but update to exact pixel height once laid out
            // to ensure the window wraps the scaled view tightly, fixing the invisible wall.
            if (keyboardRoot != null && keyboardRoot!!.height > 0) {
                // LOCK the unscaled height so the OS window resizing doesn't compress the layout natively
                keyboardRoot!!.layoutParams.height = keyboardRoot!!.height
                lp.height = (keyboardRoot!!.height * savedScale).toInt()
            } else {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                keyboardRoot?.post {
                    val currentLp = window.attributes
                    if (currentLp != null && keyboardRoot!!.height > 0) {
                        // LOCK the unscaled height
                        keyboardRoot!!.layoutParams.height = keyboardRoot!!.height
                        currentLp.height = (keyboardRoot!!.height * savedScale).toInt()
                        window.attributes = currentLp
                    }
                }
            }
            
            lp.gravity = Gravity.BOTTOM or Gravity.START
            val screenWidth = metrics.widthPixels
            lp.x = maxOf(0, minOf((screenWidth - scaledWidth) / 2, screenWidth - scaledWidth))
            lp.y = (floatingY * metrics.density).toInt()
            
            keyboardRoot?.scaleX = savedScale
            keyboardRoot?.scaleY = savedScale
            keyboardRoot?.pivotX = 0f
            keyboardRoot?.pivotY = 0f'''

new_float_init = '''            val baseWidth = (300 * metrics.density)
            val scaledWidth = (baseWidth * savedScale).toInt()
            
            lp.width = scaledWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            
            lp.gravity = Gravity.BOTTOM or Gravity.START
            val screenWidth = metrics.widthPixels
            lp.x = maxOf(0, minOf((screenWidth - scaledWidth) / 2, screenWidth - scaledWidth))
            lp.y = (floatingY * metrics.density).toInt()
            
            // Remove scaleX/Y and use native resizing
            keyboardRoot?.scaleX = 1f
            keyboardRoot?.scaleY = 1f
            keyboardRoot?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
            
            val scaledKeyHeightDp = (75 * savedScale).toInt()
            keyboardRoot?.findViewById<com.flowboard.ime.ui.KeyboardView>(R.id.keyboardView)?.setKeyHeight(scaledKeyHeightDp)'''

if old_float_init in kt:
    kt = kt.replace(old_float_init, new_float_init)
else:
    print("WARNING: Could not find old_float_init")

# 2. In handleResizeTouch, do native resizing
old_resize = '''                if (baseWidth > 0 && root.height > 0) {
                    val scale = targetWidth / baseWidth
                    root.scaleX = scale
                    root.scaleY = scale
                    
                    // Always scale from Top-Left (or Top-Right) so the top edge is stationary
                    root.pivotX = 0f
                    root.pivotY = 0f
                    
                    val targetHeight = (root.height * scale).toInt()
                    lp.width = targetWidth
                    lp.height = targetHeight
                    
                    // Because Gravity is BOTTOM, height expansion moves the top edge UP.
                    // We want the TOP edge to be stationary, so we push the window DOWN by the height difference.
                    lp.y = resizeInitialWindowY - (targetHeight - resizeInitialHeight)
                    
                    // If resizing from left, lock the RIGHT edge by shifting the window LEFT
                    if (isLeftCorner) {
                        lp.x = resizeInitialWindowX - (targetWidth - resizeInitialWidth)
                    }
                    
                    val density = if (metrics.density > 0) metrics.density else 1.0f
                    floatingY = (lp.y / density).toInt()
                    
                    window.attributes = lp
                    window.decorView.requestLayout()
                    
                    // Save scale so it persists across resets
                    getSharedPreferences("flowboard_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putFloat("floating_scale", scale).apply()
                }'''

new_resize = '''                if (baseWidth > 0) {
                    val scale = targetWidth / baseWidth
                    
                    lp.width = targetWidth
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    
                    val scaledKeyHeightDp = (75 * scale).toInt()
                    root.findViewById<com.flowboard.ime.ui.KeyboardView>(R.id.keyboardView)?.setKeyHeight(scaledKeyHeightDp)
                    
                    // Natively expanding layout width/height. 
                    // Because Gravity is BOTTOM, if the keyboard gets taller, it expands UPWARDS.
                    // This perfectly keeps the BOTTOM edge stationary!
                    // Wait, we WANT the top edge to be stationary, not the bottom!
                    // To keep top edge stationary, we push the window DOWN by the exact height difference.
                    // But we don't know the exact new height yet until layout pass.
                    // However, we can approximate the height difference based on the change in keyHeightPx.
                    val oldKeyHeightPx = (75 * (resizeInitialWidth / baseWidth) * metrics.density).toInt()
                    val newKeyHeightPx = (75 * scale * metrics.density).toInt()
                    val heightDiff = (newKeyHeightPx - oldKeyHeightPx) * 3 // 3 rows of keys
                    
                    lp.y = resizeInitialWindowY - heightDiff
                    
                    if (isLeftCorner) {
                        lp.x = resizeInitialWindowX - (targetWidth - resizeInitialWidth)
                    }
                    
                    val density = if (metrics.density > 0) metrics.density else 1.0f
                    floatingY = (lp.y / density).toInt()
                    
                    window.attributes = lp
                    
                    getSharedPreferences("flowboard_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putFloat("floating_scale", scale).apply()
                }'''

if old_resize in kt:
    kt = kt.replace(old_resize, new_resize)
else:
    print("WARNING: Could not find old_resize")

# 3. Docked mode reset
old_docked = '''            // Reset scale down transformations back to original (1f)
            keyboardRoot?.scaleX = 1f
            keyboardRoot?.scaleY = 1f
            
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = android.view.Gravity.BOTTOM
            lp.x = 0
            lp.y = 0
            
            // Restore fixed widths for docked mode
            keyboardRoot?.layoutParams?.width = ViewGroup.LayoutParams.MATCH_PARENT
            keyboardRoot?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT'''

new_docked = '''            // Reset scale down transformations back to original (1f)
            keyboardRoot?.scaleX = 1f
            keyboardRoot?.scaleY = 1f
            keyboardRoot?.findViewById<com.flowboard.ime.ui.KeyboardView>(R.id.keyboardView)?.setKeyHeight(75)
            
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = android.view.Gravity.BOTTOM
            lp.x = 0
            lp.y = 0
            
            // Restore fixed widths for docked mode
            keyboardRoot?.layoutParams?.width = ViewGroup.LayoutParams.MATCH_PARENT
            keyboardRoot?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT'''

if old_docked in kt:
    kt = kt.replace(old_docked, new_docked)
else:
    print("WARNING: Could not find old_docked")

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)

print("Script execution completed.")
