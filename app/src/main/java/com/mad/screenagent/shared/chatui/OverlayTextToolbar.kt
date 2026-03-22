package com.mad.screenagent.shared.chatui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Custom [TextToolbar] that exposes copy/cut/paste/select-all actions as Compose state,
 * so they can be rendered as a [DropdownMenu] instead of the system [android.view.ActionMode]
 * which does not work in TYPE_APPLICATION_OVERLAY windows (floating bubble service).
 */
class OverlayTextToolbar : TextToolbar {

    var showMenu by mutableStateOf(false)
        private set
    var copyAction: (() -> Unit)? by mutableStateOf(null)
        private set
    var pasteAction: (() -> Unit)? by mutableStateOf(null)
        private set
    var cutAction: (() -> Unit)? by mutableStateOf(null)
        private set
    var selectAllAction: (() -> Unit)? by mutableStateOf(null)
        private set

    private var _status = TextToolbarStatus.Hidden
    override val status: TextToolbarStatus get() = _status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        copyAction = onCopyRequested
        pasteAction = onPasteRequested
        cutAction = onCutRequested
        selectAllAction = onSelectAllRequested
        showMenu = true
        _status = TextToolbarStatus.Shown
    }

    override fun hide() {
        showMenu = false
        _status = TextToolbarStatus.Hidden
    }
}
