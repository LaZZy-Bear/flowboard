---
name: android-ime-framework
description: Guidelines and patterns for implementing the Android Input Method Editor (IME) framework, including InputMethodService lifecycle, keyboard view rendering, connection management (EditorInfo, InputConnection), and keyboard type handling.
---

# Android IME Framework Skill

This skill provides guidelines and templates for implementing and extending the Android Input Method Editor (IME) framework in Flowboard.

## Core Component: `InputMethodService`

All Android custom keyboards must extend [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService).

### Lifecycle Management
Ensure proper lifecycle handling to avoid memory leaks and ensure snappy keyboard responsiveness:
1. **`onCreate()`**: Initialize settings, theme config, database/parsing managers, and analytics. Do not initialize heavy UI components here.
2. **`onCreateInputView()`**: Inflate and return the keyboard layout view.
3. **`onCreateCandidatesView()`**: (Optional) Inflate and return the view for word suggestions.
4. **`onStartInputView(EditorInfo info, boolean restarting)`**: Bind the keyboard state to the target input field type (e.g., text, password, number, email). Always read `EditorInfo.inputType` and `EditorInfo.imeOptions`.
5. **`onFinishInputView(boolean finishingInput)`**: Clear input states, reset prediction caches, and save metrics if necessary.
6. **`onDestroy()`**: Release resources, unregister broadcast receivers, and cancel coroutine scopes.

### Connecting to Input Field: `InputConnection`

Interact with the active input field using the [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection) retrieved via `getCurrentInputConnection()`.

#### Safely Inserting Text
```kotlin
val ic = currentInputConnection ?: return
ic.commitText(textToInsert, 1)
```

#### Safely Deleting Text
Always handle selection ranges:
```kotlin
val ic = currentInputConnection ?: return
val selectedText = ic.getSelectedText(0)
if (selectedText.isNullOrEmpty()) {
    // No selection, delete 1 character before cursor
    ic.deleteSurroundingText(1, 0)
} else {
    // Delete selection
    ic.commitText("", 1)
}
```

## IME Options & Keyboard Types

Always adjust keyboard layouts based on `EditorInfo.inputType`:

| Input Type Class | IME Variations | Action Button handling |
| :--- | :--- | :--- |
| `TYPE_CLASS_NUMBER` / `TYPE_NUMBER_VARIATION_PASSWORD` | Numberpad or PIN keyboard | Show numeric keys |
| `TYPE_TEXT_VARIATION_EMAIL_ADDRESS` | Display `@` and `.com` shortcuts | `IME_ACTION_SEND` or `IME_ACTION_GO` |
| `TYPE_TEXT_VARIATION_URI` | Display `/` and `.com` keys | `IME_ACTION_GO` |
| `TYPE_TEXT_VARIATION_PASSWORD` | Disable suggestion/candidates view | Disable swipe-to-type |

### Handling Action Buttons
Use `EditorInfo.imeOptions` to update the Enter key icon and action:
```kotlin
val actionId = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
when (actionId) {
    EditorInfo.IME_ACTION_GO -> { /* Show Go icon, perform Go */ }
    EditorInfo.IME_ACTION_SEARCH -> { /* Show Search icon, perform Search */ }
    EditorInfo.IME_ACTION_SEND -> { /* Show Send icon, perform Send */ }
    EditorInfo.IME_ACTION_NEXT -> { /* Focus next field */ }
    else -> { /* Default carriage return */ }
}
```
To trigger the action programmatically:
```kotlin
ic.performEditorAction(actionId)
```
