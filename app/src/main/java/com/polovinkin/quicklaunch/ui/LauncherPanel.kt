package com.polovinkin.quicklaunch.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Trace
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.polovinkin.quicklaunch.QuickLaunchApp
import com.polovinkin.quicklaunch.R
import com.polovinkin.quicklaunch.clipboard.ClipboardLinkSource
import com.polovinkin.quicklaunch.index.AppEntry
import com.polovinkin.quicklaunch.launch.LaunchResult
import com.polovinkin.quicklaunch.search.Ranker
import com.polovinkin.quicklaunch.search.TextNormalizer

/**
 * The search UI, independent of how it is hosted. [LaunchActivity] hosts it in an activity window
 * (fallback); [com.polovinkin.quicklaunch.overlay.OverlayController] hosts it in a system overlay window.
 * Everything here runs on the main thread and is sub-millisecond except the first layout.
 */
class LauncherPanel(
    private val app: QuickLaunchApp,
    /** The view that receives window insets and whose padding tracks the IME. */
    private val windowRoot: View,
    private val host: Host,
) {
    interface Host {
        /** Close the panel immediately, no animation. */
        fun dismiss()

        /** A system drag started from a row: make the window pass-through so drop zones underneath get the drop. */
        fun onDragStarted()

        /** The drag ended without a drop: restore the window. */
        fun onDragCancelled()
    }

    private var imeWasVisible = false

    private val index = app.index
    private val icons = app.icons
    private val launcher = app.launcher
    private val clipboard = app.clipboardLinks
    private val res = windowRoot.resources

    private val scrim: View = windowRoot.findViewById(R.id.root)
    private val rowMenu = RowMenu(windowRoot.findViewById<FrameLayout>(R.id.root))
    private val card: View = windowRoot.findViewById(R.id.card)
    val input: EditText = windowRoot.findViewById(R.id.input)
    private val resultsView: ResultsView = windowRoot.findViewById(R.id.results)
    private val resultsScroll: ResultsScroll = windowRoot.findViewById(R.id.results_scroll)
    private val emptyView: TextView = windowRoot.findViewById(R.id.empty)
    private val footer: View = windowRoot.findViewById(R.id.footer)
    private val footerMessage: TextView = windowRoot.findViewById(R.id.hint_bar)
    private val footerAction: TextView = windowRoot.findViewById(R.id.footer_action)
    private var footerMessageSet = false
    private val setupHeader: View = windowRoot.findViewById(R.id.setup_header)
    private val usageHint = SetupRow(
        windowRoot.findViewById(R.id.usage_hint),
        R.drawable.ic_star, R.string.setup_usage_title, R.string.setup_usage_subtitle,
    )
    private val prefs = app.getSharedPreferences("ql", android.content.Context.MODE_PRIVATE)

    private val results = ArrayList<AppEntry>(Ranker.MAX_RESULTS)
    /** URL on the clipboard when the panel opened, offered as the first row while the query is empty. */
    private var link: String? = null
    private var linkChecked = false
    private var query = ""
    /** Index over the combined list: the link row (when shown) is 0, app results follow. */
    private var selected = 0
    private var launched = false
    private var active = false
    private var dragging = false
    private var pinnedSectionShown = false

    private val iconCallback: (String, Bitmap) -> Unit = { key, bitmap -> resultsView.onIconLoaded(key, bitmap) }

    init {
        resultsView.iconLoader = icons
        resultsView.onRowClick = { entry -> launch(entry) }
        resultsView.onLinkClick = { url -> openLink(url) }
        resultsView.onRowLongPress = { entry, row -> if (!launched && !dragging) rowMenu.show(entry, row) }
        resultsView.onRowDrag = { entry, row -> startDrag(entry, row) }
        rowMenu.onAppInfo = { entry -> showAppInfo(entry) }
        resultsView.onPinClick = { entry, button -> togglePin(entry, button) }
        windowRoot.setOnDragListener { _, event ->
            if (Log.isLoggable(QuickLaunchApp.TAG, Log.DEBUG)) {
                Log.d(QuickLaunchApp.TAG, "drag event action=${event.action} result=${event.result} dragging=$dragging")
            }
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is AppEntry
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    endDrag(dropped = event.result)
                    true
                }
                else -> false
            }
        }
        scrim.setOnClickListener { host.dismiss() }
        card.setOnClickListener { /* consume: taps on the card never dismiss */ }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString() ?: "")
        })
        input.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = actionId == EditorInfo.IME_NULL && event != null && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || isEnterKey) {
                launchSelected()
            }
            true
        }
        // Tapping a setup row opens the setting; the row stays until the feature is on or the user dismisses it.
        usageHint.onDismiss = {
            prefs.edit().putBoolean(PREF_USAGE_HINT_DISMISSED, true).apply()
            updateUsageHint()
        }
        usageHint.onClick = {
            try {
                app.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: RuntimeException) {
                Log.w(QuickLaunchApp.TAG, "usage access settings unavailable", e)
            }
            host.dismiss()
        }
        installInsetsHandling()
    }

    private fun updateUsageHint() {
        val show = query.isEmpty() && !prefs.getBoolean(PREF_USAGE_HINT_DISMISSED, false) && !index.usagePermitted()
        val visibility = if (show) View.VISIBLE else View.GONE
        if (usageHint.view.visibility != visibility) {
            usageHint.view.visibility = visibility
            setupHeader.visibility = visibility
            ViewCompat.requestApplyInsets(windowRoot)
        }
    }

    /** The footer is shown only when the fallback activity offers instant mode. */
    private fun updateFooter() {
        val visibility = if (footerMessageSet) View.VISIBLE else View.GONE
        if (footer.visibility != visibility) {
            footer.visibility = visibility
            ViewCompat.requestApplyInsets(windowRoot) // the row budget depends on it
        }
    }

    /** Replace the key hints with a message and an action chip (the fallback activity offers instant mode here). */
    fun showFooterAction(message: CharSequence, action: CharSequence, iconRes: Int, onClick: () -> Unit) {
        footerMessageSet = true
        footerMessage.text = message
        footerMessage.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        footerMessage.compoundDrawableTintList = footerMessage.textColors
        footerMessage.visibility = View.VISIBLE
        footerAction.text = action
        footerAction.visibility = View.VISIBLE
        footer.setOnClickListener { onClick() }
        footerAction.setOnClickListener { onClick() }
        updateFooter()
    }

    /** View holder over row_setup.xml. */
    private class SetupRow(val view: View, iconRes: Int, titleRes: Int, subtitleRes: Int) {
        var onClick: (() -> Unit)? = null
        var onDismiss: (() -> Unit)? = null

        init {
            view.findViewById<ImageView>(R.id.setup_icon).setImageResource(iconRes)
            view.findViewById<TextView>(R.id.setup_title).setText(titleRes)
            view.findViewById<TextView>(R.id.setup_subtitle).setText(subtitleRes)
            view.setOnClickListener { onClick?.invoke() }
            view.findViewById<View>(R.id.setup_dismiss).setOnClickListener { onDismiss?.invoke() }
        }
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    /** Called every time the panel becomes visible. Renders cached results synchronously. */
    fun onShown() {
        active = true
        launched = false
        imeWasVisible = false
        link = null
        linkChecked = false
        index.listener = { onIndexChanged() }
        updateFooter()
        if (input.text.isNotEmpty()) input.setText("") else rerank()
        // Succeeds only if the window already has focus (a re-show); otherwise onWindowFocusGained retries.
        refreshLink()
        input.requestFocus()
        // After the first frame is committed: revalidate the index and warm the icon cache.
        windowRoot.post {
            if (!active) return@post
            index.revalidate(force = false)
            icons.prewarm(index.snapshot, iconCallback)
        }
    }

    fun onHidden() {
        active = false
        rowMenu.hide()
        dragging = false
        windowRoot.removeCallbacks(dragWatchdog)
        index.listener = null
    }

    /** Android 10+ releases the clipboard only to the focused window; hosts call this when focus arrives. */
    fun onWindowFocusGained() {
        if (!active || linkChecked) return
        refreshLink()
    }

    // ---- Clipboard link -----------------------------------------------------------------------

    /** Reads the clipboard once per show. A dropped drag or a refocus never re-reads. */
    private fun refreshLink() {
        val found = clipboard.currentLink()
        if (found == null && !windowRoot.hasWindowFocus()) return // not yet allowed to read; try again on focus
        linkChecked = true
        if (found == link) return
        link = found
        if (Log.isLoggable(QuickLaunchApp.TAG, Log.DEBUG)) Log.d(QuickLaunchApp.TAG, "clipboard link=${found != null}")
        selected = 0
        bindResults()
    }

    /** The link row only competes with the empty-query list; typing means the user wants an app. */
    private fun visibleLink(): String? = if (query.isEmpty()) link else null

    /**
     * Pinned apps lead the empty-query list (the ranker puts them first), shown as their own section.
     * Typed results are one ranked list, so the section is hidden there.
     */
    private fun pinnedCount(): Int {
        if (query.isNotEmpty()) return 0
        var n = 0
        while (n < results.size && results[n].pinOrder >= 0) n++
        return n
    }

    private fun bindResults() = resultsView.bind(visibleLink(), results, pinnedCount(), selected, iconCallback)

    /** Rows the user can select: the link row plus app results. */
    private fun rowCount(): Int = (if (visibleLink() != null) 1 else 0) + results.size

    private fun openLink(url: String) {
        if (launched) return
        launched = true
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
            host.dismiss()
        } catch (e: ActivityNotFoundException) {
            launched = false
            Toast.makeText(app, R.string.error_no_browser, Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            launched = false
            Log.w(QuickLaunchApp.TAG, "open link failed", e)
            Toast.makeText(app, R.string.error_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Insets --------------------------------------------------------------------------------

    private fun installInsetsHandling() {
        val topOffsetMin = res.getDimensionPixelSize(R.dimen.card_top_offset)
        val sideMargin = res.getDimensionPixelSize(R.dimen.card_margin_h)
        val maxWidth = res.getDimensionPixelSize(R.dimen.card_max_width)
        val rowHeight = res.getDimensionPixelSize(R.dimen.row_height)
        val headerHeight = res.getDimensionPixelSize(R.dimen.section_header_height)
        val separatorHeight = res.getDimensionPixelSize(R.dimen.section_separator_height)
        val fixedChrome = res.getDimensionPixelSize(R.dimen.input_height) + res.getDimensionPixelSize(R.dimen.card_padding) * 2
        // The footer (the instant mode offer) only takes room when shown.
        val footerHeight = (rowHeight * 0.85f).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(windowRoot) { v, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime: Insets = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Back with the keyboard up hides the keyboard first; treat that as "cancel".
            val imeVisible = ime.bottom > 0
            if (active && !dragging && imeWasVisible && !imeVisible) {
                imeWasVisible = false
                host.dismiss()
                return@setOnApplyWindowInsetsListener insets
            }
            imeWasVisible = imeVisible

            val screenWidth = if (v.width > 0) v.width else res.displayMetrics.widthPixels
            val screenHeight = if (v.height > 0) v.height else res.displayMetrics.heightPixels
            val bottom = maxOf(bars.bottom, ime.bottom) + sideMargin
            v.setPadding(bars.left, 0, bars.right, bottom)

            val availableWidth = screenWidth - bars.left - bars.right - sideMargin * 2
            val cardWidth = minOf(availableWidth, maxWidth)
            val topMargin = bars.top + maxOf(topOffsetMin, (screenHeight * 0.08f).toInt())

            val lp = card.layoutParams as ViewGroup.MarginLayoutParams
            if (lp.width != cardWidth || lp.topMargin != topMargin) {
                lp.width = cardWidth
                lp.topMargin = topMargin
                card.layoutParams = lp
            }

            val hintRows = if (usageHint.view.visibility == View.VISIBLE) 1 else 0
            val hintRow = hintRows * rowHeight + (if (hintRows > 0) headerHeight else 0)
            val pinnedSection = if (pinnedCount() > 0) headerHeight + separatorHeight else 0
            val footerSpace = if (footer.visibility == View.VISIBLE) footerHeight else 0
            val available = screenHeight - topMargin - bottom - fixedChrome - footerSpace - hintRow - pinnedSection
            // Every result is bound and the list scrolls within the space left, so nothing is dropped. The
            // viewport is that exact space: a half-visible last row shows there is more.
            val maxRows = Ranker.MAX_RESULTS
            // The Pinned header and hairline scroll with the rows, so they are part of the viewport.
            val inList = if (pinnedCount() > 0) headerHeight + separatorHeight else 0
            resultsScroll.maxHeight = maxOf(rowHeight, available + inList)
            if (resultsView.maxVisible != maxRows) {
                resultsView.maxVisible = maxRows
                bindResults()
            }
            insets
        }
        // Fold/unfold, rotation and DeX resize change the root size without new insets: recompute then.
        windowRoot.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) ViewCompat.requestApplyInsets(v)
        }
    }

    // ---- Search --------------------------------------------------------------------------------

    private fun onQueryChanged(raw: String) {
        rowMenu.hide()
        query = TextNormalizer.normalizeQuery(raw)
        selected = 0
        rerank()
    }

    private fun onIndexChanged() {
        rerank(keepSelection = true)
    }

    /**
     * @param keepSelection keep the selected index where it is (clamped)
     * @param follow keep the selection on this entry wherever it lands, or row 0 if it fell off the list
     */
    private fun rerank(keepSelection: Boolean = false, follow: AppEntry? = null) {
        Trace.beginSection("ql.rank")
        val entries = index.awaitSnapshot()
        Ranker.rank(entries, query, System.currentTimeMillis(), results)
        Trace.endSection()

        val rows = rowCount()
        val followed = if (follow != null) results.indexOf(follow) else -1
        selected = when {
            rows == 0 -> 0
            followed >= 0 -> (followed + (if (visibleLink() != null) 1 else 0)).coerceAtMost(resultsView.maxVisible - 1)
            keepSelection -> selected.coerceIn(0, rows - 1)
            else -> 0
        }

        Trace.beginSection("ql.bind")
        bindResults()
        emptyView.visibility = if (results.isEmpty() && query.isNotEmpty()) View.VISIBLE else View.GONE
        updateUsageHint()
        // The pinned section takes header space away from rows; recompute the row budget when it toggles.
        val sectioned = pinnedCount() > 0
        if (sectioned != pinnedSectionShown) {
            pinnedSectionShown = sectioned
            ViewCompat.requestApplyInsets(windowRoot)
        }
        Trace.endSection()
    }

    // ---- Pins ----------------------------------------------------------------------------------

    /** The app entry at a combined-list row index, or null for the link row / out of range. */
    private fun entryAt(row: Int): AppEntry? {
        val offset = if (visibleLink() != null) 1 else 0
        return results.getOrNull(row - offset)
    }

    /** Pin or unpin and re-rank so the row moves where it now belongs, keeping the selection on it. */
    private fun togglePin(entry: AppEntry, feedback: View) {
        if (launched) return
        val pinned = index.togglePin(entry)
        feedback.performHapticFeedback(
            if (pinned) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK,
        )
        if (Log.isLoggable(QuickLaunchApp.TAG, Log.DEBUG)) Log.d(QuickLaunchApp.TAG, "pin ${entry.key} -> $pinned")
        rerank(follow = entry)
    }

    // ---- Keys ----------------------------------------------------------------------------------

    /** Returns true when the key was consumed. Call from the host's dispatchKeyEvent before super. */
    fun handleKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (down && event.repeatCount == 0) launchSelected()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (down) if (rowMenu.isShowing) rowMenu.hide() else host.dismiss()
                return true
            }
        }
        return false
    }

    // ---- Long-press menu -----------------------------------------------------------------------

    /** The system App info screen for the app, then close the panel. */
    private fun showAppInfo(entry: AppEntry) {
        if (launched) return
        val bounds = Rect().takeIf { card.getGlobalVisibleRect(it) }
        if (!launcher.showDetails(entry, bounds)) {
            Toast.makeText(app, R.string.error_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        launched = true
        host.dismiss()
    }

    // ---- Drag to split screen ------------------------------------------------------------------

    private companion object {
        // Stored under the old "tapped" name so users who acted on the previous hint are not asked again.
        const val PREF_USAGE_HINT_DISMISSED = "usage_hint_tapped"
    }

    private val dragWatchdog = Runnable { endDrag(dropped = false) }

    private fun startDrag(entry: AppEntry, row: View): Boolean {
        if (dragging || !AppDrag.canDrag(entry)) return false
        rowMenu.hide() // the finger slid on from the long press: the drag replaces the menu
        // Hiding the keyboard here must not be read as "user pressed Back" by the insets listener.
        imeWasVisible = false
        KeyboardUtil.hideIme(input)
        if (!AppDrag.start(row, entry)) {
            Log.w(QuickLaunchApp.TAG, "startDragAndDrop refused for ${entry.key}")
            return false
        }
        if (Log.isLoggable(QuickLaunchApp.TAG, Log.DEBUG)) Log.d(QuickLaunchApp.TAG, "drag started for ${entry.key}")
        dragging = true
        host.onDragStarted()
        // Safety net: if the system never reports the end of the drag, never leave an invisible window behind.
        windowRoot.postDelayed(dragWatchdog, 15_000L)
        return true
    }

    private fun endDrag(dropped: Boolean) {
        if (!dragging) return
        dragging = false
        windowRoot.removeCallbacks(dragWatchdog)
        if (dropped) host.dismiss() else host.onDragCancelled()
    }

    // ---- Launch --------------------------------------------------------------------------------

    private fun launchSelected() = launchRow(selected)

    /** Launch whatever sits at a combined-list row index: the link row or an app. Ignores rows not on screen. */
    private fun launchRow(row: Int) {
        if (row < 0 || row >= minOf(rowCount(), resultsView.maxVisible)) return
        val url = visibleLink()
        if (url != null && row == 0) {
            openLink(url)
            return
        }
        launch(entryAt(row) ?: return)
    }

    private fun launch(entry: AppEntry) {
        if (launched) return
        launched = true
        val t0 = SystemClock.elapsedRealtimeNanos()
        when (launcher.launch(entry, null)) {
            LaunchResult.OK -> {
                index.recordLaunch(entry)
                if (Log.isLoggable(QuickLaunchApp.TAG, Log.DEBUG)) {
                    Log.d(QuickLaunchApp.TAG, "launch ${entry.component.flattenToShortString()} in ${(SystemClock.elapsedRealtimeNanos() - t0) / 1000} us")
                }
                host.dismiss()
            }
            LaunchResult.PAUSED -> {
                launched = false
                Toast.makeText(app, R.string.error_work_paused, Toast.LENGTH_SHORT).show()
            }
            LaunchResult.NOT_AVAILABLE -> {
                launched = false
                Toast.makeText(app, R.string.error_not_available, Toast.LENGTH_SHORT).show()
                index.removeEntry(entry)
            }
        }
    }
}
