# Resize Feature Fixes

- `[x]` 1. **Docked Resize via Toolbar:**
  - `[x]` Add `setKeyHeight(dp: Int)` to `KeyboardView.kt`.
  - `[x]` Hide `ToolbarAction.RESIZE` in `FlowboardIMEService.kt` when in floating mode.
  - `[x]` Bind `heightSeekBar` to adjust keyboard height and save/load from `SharedPreferences`.
- `[x]` 2. **Enlarge Floating Resize Touch Targets:**
  - `[x]` Update `resizeHandleLeft` and `resizeHandleRight` in `keyboard_layout.xml` to `64dp`.
- `[x]` 3. **Fix Floating Resize Clipping:**
  - `[x]` Update `handleResizeTouch` in `FlowboardIMEService.kt` to adjust `lp.width` and `lp.height` based on scale.
  - `[x]` Force `keyboardRoot` layout parameters to unscaled size to avoid double scaling.
- `[x]` 4. **Fix Floating Drag Invisible Wall:**
  - `[x]` (Implicitly solved by fixing `lp.width` in step 3).
- `[ ]` 5. **Verify:**
  - `[ ]` Compile and test logical paths.
