/*
 * Copyright (C) 2009 Jeff Sharkey, http://jsharkey.org/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jsharkey.sky;

import org.jsharkey.sky.ForecastProvider.AppWidgets;
import org.jsharkey.sky.ForecastProvider.AppWidgetsColumns;
import org.jsharkey.sky.ForecastProvider.ForecastsColumns;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Definition of a large-sized forecast widget. Passes any requested updates to
 * {@link UpdateService} to perform on background thread and prevent ANR.
 */
public class LargeAppWidget extends AppWidgetProvider {
	private static final String TAG = "LargeAppWidget";

	private static final String[] PROJECTION_APPWIDGETS = new String[] { AppWidgetsColumns.TITLE,
			AppWidgetsColumns.CURRENT_TEMP, AppWidgetsColumns.TEMP_UNIT, AppWidgetsColumns.UPDATE_STATUS,
			AppWidgetsColumns.SKIN };

	private static final int COL_TITLE = 0;
	private static final int COL_CURRENT_TEMP = 1;
	private static final int COL_TEMP_UNIT = 2;
	private static final int COL_UPDATE_STATUS = 3;
	private static final int COL_SKIN = 4;

	private static final String[] PROJECTION_FORECASTS = new String[] { ForecastsColumns.TEMP_HIGH,
			ForecastsColumns.TEMP_LOW, ForecastsColumns.ICON_URL, ForecastsColumns.VALID_START };

	private static final int COL_TEMP_HIGH = 0;
	private static final int COL_TEMP_LOW = 1;
	private static final int COL_ICON_URL = 2;
	private static final int COL_VALID_START = 3;


	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
		// If no specific widgets requested, collect list of all
		if (appWidgetIds == null) {
			appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(context, LargeAppWidget.class));
		}

		// Request update for these widgets and launch updater service
		UpdateService.requestUpdate(appWidgetIds);
		context.startService(new Intent(context, UpdateService.class));
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void onDeleted(Context context, int[] appWidgetIds) {
		ContentResolver resolver = context.getContentResolver();
		for (int appWidgetId : appWidgetIds) {
			Log.d(TAG, "Deleting appWidgetId=" + appWidgetId);
			Uri appWidgetUri = ContentUris.withAppendedId(AppWidgets.CONTENT_URI, appWidgetId);
			resolver.delete(appWidgetUri, null, null);
		}
	}

	/**
	 * Build an update for the given large widget. Should only be called from a
	 * service or thread to prevent ANR during database queries.
	 */
	public static RemoteViews buildUpdate(Context context, Uri appWidgetUri) {
		Log.d(TAG, "Building large widget update");

		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_large);

		boolean daytime = ForecastUtils.isDaytime();
		boolean forecastFilled = false;
		String temp_unit_str = "";
		int current_temp = 0;
		int update_status = AppWidgetsColumns.UPDATE_STATUS_FAILURE;

		String skinName = "";
		boolean useSkin = false;
			
		ContentResolver resolver = context.getContentResolver();
		Resources res = context.getResources();

		Cursor cursor = null;

		// Pull out widget title and desired temperature units
		try {
			cursor = resolver.query(appWidgetUri, PROJECTION_APPWIDGETS, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				String title = cursor.getString(COL_TITLE);
				temp_unit_str = cursor.getString(COL_TEMP_UNIT);
				current_temp = cursor.getInt(COL_CURRENT_TEMP);
				update_status = cursor.getInt(COL_UPDATE_STATUS);
				skinName = cursor.getString(COL_SKIN);

				if (skinName.equals(""))
					useSkin = false;
				else
					useSkin = true;
				
				views.setTextViewText(R.id.location, title);
			}
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		// Find the forecast nearest now and build update using it
		try {
			Uri forecastUri = Uri.withAppendedPath(appWidgetUri, AppWidgets.TWIG_FORECASTS);

			cursor = resolver.query(forecastUri, PROJECTION_FORECASTS, null, null, null);

			if (cursor != null && cursor.moveToFirst()) {

				String icon_url = "";
				Bitmap iconResource;
				Time mTime = new Time();
				String dayOfWeek = "";
				int tempHigh = 0;
				int tempLow = 0;

				// update status
				if (update_status == AppWidgetsColumns.UPDATE_STATUS_FAILURE)
					views.setTextViewText(R.id.update_status, "*");
				else
					views.setTextViewText(R.id.update_status, "");

				// current temp
				views.setTextViewText(R.id.current_temp, ((Integer) current_temp).toString() + temp_unit_str);

				// day 1
				icon_url = cursor.getString(COL_ICON_URL);
				iconResource = ForecastUtils.getIconBitmapForForecast(context, icon_url, daytime, useSkin, skinName);
				views.setImageViewBitmap(R.id.icon1, iconResource);
				mTime.set(cursor.getLong(COL_VALID_START));
				dayOfWeek = DateUtils.getDayOfWeekString(mTime.weekDay + 1, DateUtils.LENGTH_MEDIUM).toUpperCase();
				views.setTextViewText(R.id.day1, dayOfWeek);
				tempHigh = cursor.getInt(COL_TEMP_HIGH);
				tempLow = cursor.getInt(COL_TEMP_LOW);
				views.setTextViewText(R.id.temp_day1, ((Integer) tempHigh).toString() + "/"
						+ ((Integer) tempLow).toString() + temp_unit_str);

				// day 2
				cursor.moveToNext();
				icon_url = cursor.getString(COL_ICON_URL);
				iconResource = ForecastUtils.getIconBitmapForForecast(context, icon_url, daytime, useSkin, skinName);
				views.setImageViewBitmap(R.id.icon2, iconResource);
				mTime.set(cursor.getLong(COL_VALID_START));
				dayOfWeek = DateUtils.getDayOfWeekString(mTime.weekDay + 1, DateUtils.LENGTH_MEDIUM).toUpperCase();
				views.setTextViewText(R.id.day2, dayOfWeek);
				tempHigh = cursor.getInt(COL_TEMP_HIGH);
				tempLow = cursor.getInt(COL_TEMP_LOW);
				views.setTextViewText(R.id.temp_day2, ((Integer) tempHigh).toString() + "/"
						+ ((Integer) tempLow).toString() + temp_unit_str);

				// day 3
				cursor.moveToNext();
				icon_url = cursor.getString(COL_ICON_URL);
				iconResource = ForecastUtils.getIconBitmapForForecast(context, icon_url, daytime, useSkin, skinName);
				views.setImageViewBitmap(R.id.icon3, iconResource);
				mTime.set(cursor.getLong(COL_VALID_START));
				dayOfWeek = DateUtils.getDayOfWeekString(mTime.weekDay + 1, DateUtils.LENGTH_MEDIUM).toUpperCase();
				views.setTextViewText(R.id.day3, dayOfWeek);
				tempHigh = cursor.getInt(COL_TEMP_HIGH);
				tempLow = cursor.getInt(COL_TEMP_LOW);
				views.setTextViewText(R.id.temp_day3, ((Integer) tempHigh).toString() + "/"
						+ ((Integer) tempLow).toString() + temp_unit_str);

				// day 4
				cursor.moveToNext();
				icon_url = cursor.getString(COL_ICON_URL);
				iconResource = ForecastUtils.getIconBitmapForForecast(context, icon_url, daytime, useSkin, skinName);
				views.setImageViewBitmap(R.id.icon4, iconResource);
				mTime.set(cursor.getLong(COL_VALID_START));
				dayOfWeek = DateUtils.getDayOfWeekString(mTime.weekDay + 1, DateUtils.LENGTH_MEDIUM).toUpperCase();
				views.setTextViewText(R.id.day4, dayOfWeek);
				tempHigh = cursor.getInt(COL_TEMP_HIGH);
				tempLow = cursor.getInt(COL_TEMP_LOW);
				views.setTextViewText(R.id.temp_day4, ((Integer) tempHigh).toString() + "/"
						+ ((Integer) tempLow).toString() + temp_unit_str);

				forecastFilled = true;
			}
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}

		// If not filled correctly, show error message and hide other fields
		if (!forecastFilled) {
			views = new RemoteViews(context.getPackageName(), R.layout.widget_loading);
			views.setTextViewText(R.id.loading, res.getString(R.string.widget_error));
		}

		// Connect click intent to launch details
		Intent detailIntent = new Intent(context, DetailsActivity.class);
		detailIntent.setData(appWidgetUri);

		PendingIntent pending = PendingIntent.getActivity(context, 0, detailIntent, 0);

		views.setOnClickPendingIntent(R.id.widget, pending);

		return views;
	}
}
