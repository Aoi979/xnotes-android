package com.xnotes.platform

import android.os.Build
import android.view.InputDevice
import android.view.View

/**
 * Disable input batching only while a stroke is rendered by the front buffer. Ordinary rendering
 * needs batching so one frame's samples can share a repaint instead of repainting for every sample.
 *
 * The persistent API accepts source classes, so POINTER covers all pointer devices. Callers enable
 * it only for an active front-buffer stroke and restore normal dispatch when that stroke finishes.
 * Unlike the MotionEvent overload, it can also be enabled after the stroke's first event.
 */
internal fun View.setUnbufferedStrokeInput(enabled: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        requestUnbufferedDispatch(
            if (enabled) InputDevice.SOURCE_CLASS_POINTER else InputDevice.SOURCE_CLASS_NONE,
        )
    }
}
