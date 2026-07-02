import re

kt_path = r'c:\Users\satht\Documents\Flowboard-android\app\src\main\java\com\flowboard\ime\service\FlowboardIMEService.kt'
with open(kt_path, 'r', encoding='utf-8') as f:
    kt = f.read()

# Change 1: heightSeekBar and setKeyHeight
old_seekbar = '''        heightSeekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val adjustedHeight = (progress * resources.displayMetrics.density).toInt()
                keyboardView?.let { kv ->
                    val lp = kv.layoutParams
                    lp.height = maxOf((100 * resources.displayMetrics.density).toInt(), adjustedHeight)
                    kv.layoutParams = lp
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })'''

new_seekbar = '''        val prefs = getSharedPreferences("flowboard_settings", android.content.Context.MODE_PRIVATE)
        val initialHeightDp = prefs.getInt("docked_keyboard_height", 75)
        keyboardView?.setKeyHeight(initialHeightDp)

        heightSeekBar?.max = 100
        heightSeekBar?.progress = ((initialHeightDp - 50) / 70f * 100).toInt()

        heightSeekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newHeightDp = 50 + (progress / 100f * 70).toInt()
                    keyboardView?.setKeyHeight(newHeightDp)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val newHeightDp = 50 + ((seekBar?.progress ?: 0) / 100f * 70).toInt()
                prefs.edit().putInt("docked_keyboard_height", newHeightDp).apply()
            }
        })'''
kt = kt.replace(old_seekbar, new_seekbar)

# Change 2: renderToolbar hiding RESIZE for floating mode
old_render = '''        val displayActions = if (isFloatingMode) {
            listOf(ToolbarAction.DELETE) + activeShortcuts.take(3) + ToolbarAction.MORE
        } else {
            activeShortcuts.take(4) + ToolbarAction.MORE
        }'''

new_render = '''        val displayActions = if (isFloatingMode) {
            val floatingShortcuts = activeShortcuts.filter { it != ToolbarAction.RESIZE }
            listOf(ToolbarAction.DELETE) + floatingShortcuts.take(3) + ToolbarAction.MORE
        } else {
            activeShortcuts.take(4) + ToolbarAction.MORE
        }'''
kt = kt.replace(old_render, new_render)

# Change 3: Fix double scaling in updateFloatingWindowMode
# Instead of scaling here, we just make sure bounds are reset if docked.
# Wait, let's look at updateFloatingWindowMode's scale logic.
old_update = '''            val gd = android.graphics.drawable.GradientDrawable()
            gd.cornerRadius = 16 * density
            gd.setColor(com.flowboard.ime.util.ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode()).keyboardBackground)
            keyboardRoot?.background = gd
            dragHandleArea?.visibility = View.VISIBLE
            predictionRow?.visibility = View.GONE
            setupDragHandle()
        } else {
            // Remove rounded background for docked mode
            val prefs = getSharedPreferences("flowboard_settings", android.content.Context.MODE_PRIVATE)
            val activeTheme = prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
            keyboardRoot?.setBackgroundColor(com.flowboard.ime.util.ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode()).keyboardBackground)
            dragHandleArea?.visibility = View.GONE
            predictionRow?.visibility = View.VISIBLE
            
            // Reset to full width explicitly
            window?.window?.let { w ->
                val lp = w.attributes
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                w.attributes = lp
            }
            
            // Reset scaling
            keyboardRoot?.scaleX = 1f
            keyboardRoot?.scaleY = 1f
        }'''

new_update = '''            val gd = android.graphics.drawable.GradientDrawable()
            gd.cornerRadius = 16 * density
            gd.setColor(com.flowboard.ime.util.ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode()).keyboardBackground)
            keyboardRoot?.background = gd
            dragHandleArea?.visibility = View.VISIBLE
            predictionRow?.visibility = View.GONE
            
            // Ensure layout params are fixed to 300dp internally for proportional scaling
            keyboardRoot?.layoutParams?.width = (300 * density).toInt()
            
            setupDragHandle()
        } else {
            // Remove rounded background for docked mode
            val prefs = getSharedPreferences("flowboard_settings", android.content.Context.MODE_PRIVATE)
            val activeTheme = prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
            keyboardRoot?.setBackgroundColor(com.flowboard.ime.util.ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode()).keyboardBackground)
            dragHandleArea?.visibility = View.GONE
            predictionRow?.visibility = View.VISIBLE
            
            // Reset to full width explicitly
            window?.window?.let { w ->
                val lp = w.attributes
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                w.attributes = lp
            }
            
            // Reset scaling and fixed width
            keyboardRoot?.layoutParams?.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            keyboardRoot?.scaleX = 1f
            keyboardRoot?.scaleY = 1f
        }'''
kt = kt.replace(old_update, new_update)

# Change 4: handleResizeTouch update lp.width and lp.height to avoid clipping
old_resize = '''                val baseWidth = (300 * metrics.density)
                if (baseWidth > 0) {
                    val scale = targetWidth / baseWidth
                    root.scaleX = scale
                    root.scaleY = scale
                    root.pivotX = if (isLeftCorner) root.width.toFloat() else 0f
                    root.pivotY = root.height.toFloat()
                }
                
                return true
            }'''

new_resize = '''                val baseWidth = (300 * metrics.density)
                if (baseWidth > 0) {
                    val scale = targetWidth / baseWidth
                    root.scaleX = scale
                    root.scaleY = scale
                    root.pivotX = if (isLeftCorner) root.width.toFloat() else 0f
                    root.pivotY = root.height.toFloat()
                    
                    // Fix clipping by expanding the actual window bounds
                    lp.width = targetWidth
                    lp.height = (root.height * scale).toInt()
                    window.attributes = lp
                }
                
                return true
            }'''
kt = kt.replace(old_resize, new_resize)

with open(kt_path, 'w', encoding='utf-8') as f:
    f.write(kt)
    
print("Update 9 successful")
