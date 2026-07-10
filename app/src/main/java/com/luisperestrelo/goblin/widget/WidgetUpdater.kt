package com.luisperestrelo.goblin.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Single place to re-render the home-screen widget after data changes. */
@Singleton
class WidgetUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun update() {
        GoblinWidget().updateAll(context)
    }
}
