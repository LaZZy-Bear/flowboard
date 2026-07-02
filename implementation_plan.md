# Resize Feature Fixes and Enhancements

This plan addresses the 4 issues reported regarding the resize functionality in both docked and floating modes.

## Proposed Changes

### 1. Docked Resize via Toolbar (Issue 1)
- **Goal:** Enable adjusting keyboard height using the `ToolbarAction.RESIZE` button exclusively for docked mode.
- **Changes in `KeyboardView.kt`:** 
  - Expose a method `setKeyHeight(dp: Int)` to dynamically change `keyHeightDp` and `keyHeightPx`, triggering `requestLayout()`.
- **Changes in `FlowboardIMEService.kt`:**
  - Modify `renderToolbar()` to explicitly hide the `ToolbarAction.RESIZE` button when in Floating Mode (since floating has bottom corner handles).
  - Bind the `heightSeekBar` in `heightAdjustLayout` to `keyboardView.setKeyHeight()` with a constrained bounds (e.g., Min 50dp to Max 120dp).
  - Save the selected height to `SharedPreferences` so it persists across sessions.

### 2. Enlarge Floating Resize Touch Targets (Issue 2)
- **Goal:** Make the floating resize grab handles at the bottom left/right easier to press.
- **Changes in `keyboard_layout.xml`:**
  - Increase `resizeHandleLeft` and `resizeHandleRight` size from `32dp` to `64dp` (or `48dp` standard) to ensure they are large enough for comfortable finger grabs without overlapping internal keys.

### 3. Floating Resize Cut-off / Clipping (Issue 3)
- **Goal:** Prevent the keyboard from being clipped off when scaled to large sizes in floating mode.
- **Root Cause:** Currently, `scaleX` and `scaleY` are applied to the `keyboardRoot` layout, but the Dialog Window (`lp.width`) remains statically set at 300dp wide. Android clips anything drawn outside the Window bounds.
- **Changes in `FlowboardIMEService.kt` (`handleResizeTouch` & `updateFloatingWindowMode`):**
  - Lock the inner `keyboardRoot` layout dimensions to exactly `300dp` width and wrap-content height.
  - Dynamically calculate the visual scaled width and height (`300dp * scaleX`, `unscaledHeight * scaleY`).
  - Apply these scaled dimensions to the **Dialog Window** (`window.attributes.width` and `height`).
  - This perfectly sizes the window bounding box to match the scaled keyboard, eliminating clipping without causing layout distortion.

### 4. Floating Drag Invisible Wall (Issue 4)
- **Goal:** Allow the user to drag the keyboard all the way to the edge of the screen after scaling.
- **Root Cause:** The drag constraints logic uses `lp.width` to calculate the max X coordinate, which was stuck at the unscaled 300dp.
- **Resolution:** By fixing Issue 3 (dynamically updating `lp.width` to the scaled size), the `setupDragHandle()` drag boundary calculations (`val maxValX = screenWidth - lp.width`) will automatically use the correct scaled dimensions. The invisible wall will disappear.

## User Review Required
> [!IMPORTANT]
> The Docked Resize bounds will be set to minimum `50dp` (per key row) and maximum `120dp` (per key row). Does this range look appropriate, or would you like it larger/smaller?

Please review and confirm if this plan covers all the issues you experienced with the resize functionality!
