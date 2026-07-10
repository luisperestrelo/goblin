package com.luisperestrelo.goblin.widget

import com.luisperestrelo.goblin.domain.model.Money

/** Dominant state the widget renders. */
enum class WidgetStatus { SETUP_REQUIRED, NEEDS_REAUTH, READY }

/**
 * Everything the home-screen widget needs for one render, for the single primary
 * account. Assembled by [com.luisperestrelo.goblin.data.repo.WidgetRepository].
 */
data class WidgetData(
    val status: WidgetStatus,
    val accountLast4: String? = null,
    val balance: Money? = null,
    val spentThisWeek: Money? = null,
    /** Signed: positive = spent more than the aligned window last week. */
    val spentDeltaVsLastWeek: Money? = null,
    val receivedThisWeek: Money? = null,
    val lastSyncEpochMillis: Long? = null,
    /** No successful sync in over a day - numbers may be behind the bank. */
    val isStale: Boolean = false,
) {
    companion object {
        val SetupRequired = WidgetData(WidgetStatus.SETUP_REQUIRED)
    }
}
