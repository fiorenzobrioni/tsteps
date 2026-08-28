package com.callbackdev.tsteps.widget

import android.content.res.Resources
import com.callbackdev.tsteps.R

/**
 * The one place the widget's sentences meet a locale. [WidgetContentBuilder]
 * stays a pure value builder and receives them as [WidgetNotes]; the updater —
 * which already holds a `Context` — reads them here, once per paint.
 */
fun widgetNotes(resources: Resources): WidgetNotes = WidgetNotes(
    sensorOff = resources.getString(R.string.note_widget_sensor_off_long),
    sensorOffShort = resources.getString(R.string.note_widget_sensor_off),
    noDataYet = resources.getString(R.string.note_widget_no_data_yet),
    noData = resources.getString(R.string.note_widget_no_data),
    stepsToday = resources.getString(R.string.note_widget_steps_today)
)
