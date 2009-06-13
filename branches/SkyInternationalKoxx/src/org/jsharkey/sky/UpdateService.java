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

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.jsharkey.sky.ForecastProvider.AppWidgets;
import org.jsharkey.sky.ForecastProvider.AppWidgetsColumns;
import org.jsharkey.sky.WebserviceHelper.ForecastParseException;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Background service to build any requested widget updates. Uses a single
 * background thread to walk through an update queue, querying
 * {@link WebserviceHelper} as needed to fill database. Also handles scheduling
 * of future updates, usually in 6-hour increments.
 */
public class UpdateService extends Service implements Runnable {
	private static final String TAG = "UpdateService";

	private static final String[] PROJECTION_APPWIDGETS = new String[] { AppWidgetsColumns.UPDATE_FREQ,
			AppWidgetsColumns.CONFIGURED, AppWidgetsColumns.LAST_UPDATED, AppWidgetsColumns.UPDATE_LOCATION,
			AppWidgetsColumns.UPDATE_STATUS, };

	private static final int COL_UPDATE_FREQ = 0;
	private static final int COL_CONFIGURED = 1;
	private static final int COL_LAST_UPDATED = 2;
	private static final int COL_UPDATE_LOCATION = 3;
	private static final int COL_UPDATE_STATUS = 4;

	/**
	 * Interval to wait between background widget updates. Every 6 hours is
	 * plenty to keep background data usage low and still provide fresh data.
	 */
	private long update_interval = 6 * DateUtils.HOUR_IN_MILLIS;

	private int update_location = 0;

	private LocationManager lm;
	private LocationListener myLocationListener;

	private static final double TEST_SPEED_MULTIPLIER =  1.0;

	/**
	 * If we calculated an update too quickly in the future, wait this interval
	 * and try rescheduling.
	 */
	private static final long UPDATE_THROTTLE = (long) (10 * TEST_SPEED_MULTIPLIER * DateUtils.MINUTE_IN_MILLIS);

	/**
	 * Specific {@link Intent#setAction(String)} used when performing a full
	 * update of all widgets, usually when an update alarm goes off.
	 */
	public static final String ACTION_UPDATE_ALL = "org.jsharkey.sky.UPDATE_ALL";

	/**
	 * Number of days into the future to request forecasts for.
	 */
	private static final int FORECAST_DAYS = 4;

	/**
	 * Lock used when maintaining queue of requested updates.
	 */
	private static Object sLock = new Object();

	/**
	 * Flag if there is an update thread already running. We only launch a new
	 * thread if one isn't already running.
	 */
	private static boolean sThreadRunning = false;

	/**
	 * Internal queue of requested widget updates. You <b>must</b> access
	 * through {@link #requestUpdate(int[])} or {@link #getNextUpdate()} to make
	 * sure your access is correctly synchronized.
	 */
	private static Queue<Integer> sAppWidgetIds = new LinkedList<Integer>();

	/**
	 * Request updates for the given widgets. Will only queue them up, you are
	 * still responsible for starting a processing thread if needed, usually by
	 * starting the parent service.
	 */
	public static void requestUpdate(int[] appWidgetIds) {
		synchronized (sLock) {
			for (int appWidgetId : appWidgetIds) {
				sAppWidgetIds.add(appWidgetId);
			}
		}
	}

	/**
	 * Peek if we have more updates to perform. This method is special because
	 * it assumes you're calling from the update thread, and that you will
	 * terminate if no updates remain. (It atomically resets
	 * {@link #sThreadRunning} when none remain to prevent race conditions.)
	 */
	private static boolean hasMoreUpdates() {
		synchronized (sLock) {
			boolean hasMore = !sAppWidgetIds.isEmpty();
			if (!hasMore) {
				sThreadRunning = false;
			}
			return hasMore;
		}
	}

	/**
	 * Poll the next widget update in the queue.
	 */
	private static int getNextUpdate() {
		synchronized (sLock) {
			if (sAppWidgetIds.peek() == null) {
				return AppWidgetManager.INVALID_APPWIDGET_ID;
			} else {
				return sAppWidgetIds.poll();
			}
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();

		// initialise location refresh
		Log.d(TAG, "initialise location refresh");

		lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		myLocationListener = new myLocationListener();
		lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10 * DateUtils.MINUTE_IN_MILLIS, 0,
				myLocationListener);

	}

	/**
	 * Start this service, creating a background processing thread, if not
	 * already running. If started with {@link #ACTION_UPDATE_ALL}, will
	 * automatically add all widgets to the requested update queue.
	 */
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);

		// If requested, trigger update of all widgets
		if (ACTION_UPDATE_ALL.equals(intent.getAction())) {
			Log.d(TAG, "Requested UPDATE_ALL action");
			AppWidgetManager manager = AppWidgetManager.getInstance(this);
			requestUpdate(manager.getAppWidgetIds(new ComponentName(this, MedAppWidget.class)));
			requestUpdate(manager.getAppWidgetIds(new ComponentName(this, TinyAppWidget.class)));
			requestUpdate(manager.getAppWidgetIds(new ComponentName(this, LargeAppWidget.class)));
		}

		// Only start processing thread if not already running
		synchronized (sLock) {
			if (!sThreadRunning) {
				sThreadRunning = true;
				new Thread(this).start();
			}
		}
	}

	private class myLocationListener implements LocationListener {
		public void onLocationChanged(Location location) {

			Log.d(TAG, "Location updated !!!!!!!!!!!!!! (" + location.getLatitude() + " - " + location.getLongitude()
					+ ")");

			lm.removeUpdates(this);
		}

		public void onProviderDisabled(String provider) {
		}

		public void onProviderEnabled(String provider) {
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
		}
	};

	/**
	 * Main thread for running through any requested widget updates until none
	 * remain. Also sets alarm to perform next update.
	 */
	public void run() {
		Log.d(TAG, "Processing thread started");
		AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
		ContentResolver resolver = getContentResolver();

		boolean generalUpdateStatusOk = true;

		long now = System.currentTimeMillis();

		while (hasMoreUpdates()) {
			int appWidgetId = getNextUpdate();
			Uri appWidgetUri = ContentUris.withAppendedId(AppWidgets.CONTENT_URI, appWidgetId);

			// Check if widget is configured, and if we need to update cache
			Cursor cursor = null;
			boolean isConfigured = false;
			boolean shouldUpdate = false;
			boolean updateStatusOk = true;

			try {
				cursor = resolver.query(appWidgetUri, PROJECTION_APPWIDGETS, null, null, null);
				if (cursor != null && cursor.moveToFirst()) {

					isConfigured = cursor.getInt(COL_CONFIGURED) == AppWidgetsColumns.CONFIGURED_TRUE;
					update_interval = (long) (cursor.getInt(COL_UPDATE_FREQ) * TEST_SPEED_MULTIPLIER * DateUtils.HOUR_IN_MILLIS);
					update_location = cursor.getInt(COL_UPDATE_LOCATION);

					long lastUpdated = cursor.getLong(COL_LAST_UPDATED);
					long deltaMinutes = (now - lastUpdated) / DateUtils.MINUTE_IN_MILLIS;

					Log.d(TAG, "Delta since last forecast update is " + deltaMinutes + " min");

					Log.d(TAG, "but OK ... force update");

					shouldUpdate = true;

				}
			} catch (Exception e) {
				Log.e(TAG, "Not able to read DB data", e);
			} finally {
				if (cursor != null) {
					cursor.close();
				}
			}

			if (!isConfigured) {
				// Skip this update if not configured yet
				Log.d(TAG, "Not configured yet, so skipping update");
				continue;
			} else if (shouldUpdate) {

				if (update_location == AppWidgetsColumns.UPDATE_LOCATION_TRUE) {
					try {
						this.lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

						// check if network location provider is available
						LocationProvider provider = lm.getProvider(LocationManager.NETWORK_PROVIDER);

						if (provider != null) {

							Location mLastFix = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

							if (mLastFix != null) {
								GeocodeQuery geoResult = GeocoderGetData(new GeocodeQuery(mLastFix));

								if (geoResult != null) {
									ContentValues values = new ContentValues();

									values.put(AppWidgetsColumns.TITLE, geoResult.name);
									values.put(AppWidgetsColumns.LAT, geoResult.lat);
									values.put(AppWidgetsColumns.LON, geoResult.lon);

									ContentResolver resolver2 = getContentResolver();
									resolver2.update(appWidgetUri, values, null, null);

								} else {
									Log.d(TAG, "not able to refresh location");
									updateStatusOk = false;
								}
							} else {
								Log.d(TAG, "not able to refresh location");
								updateStatusOk = false;
							}
						}
						else
						{
							Log.d(TAG, "not able to refresh location - provider not available");
							updateStatusOk = false;
						}
					} catch (Exception e) {
						Log.e(TAG, "Problem getting location", e);
						updateStatusOk = false;
					}
				}

				// Last update is outside throttle window, so update again
				try {
					WebserviceHelper.updateForecasts(this, appWidgetUri, FORECAST_DAYS);
				} catch (ForecastParseException e) {
					Log.e(TAG, "Problem parsing forecast", e);
					updateStatusOk = false;
				}
			}

			// save update status
			ContentValues values = new ContentValues();
			if (updateStatusOk)
				values.put(AppWidgetsColumns.UPDATE_STATUS, AppWidgetsColumns.UPDATE_STATUS_OK);
			else
				values.put(AppWidgetsColumns.UPDATE_STATUS, AppWidgetsColumns.UPDATE_STATUS_FAILURE);

			ContentResolver resolver2 = getContentResolver();
			resolver2.update(appWidgetUri, values, null, null);

			// set general update status to false to request a faster update
			if (updateStatusOk = false)
				generalUpdateStatusOk = false;
			
			// Process this update through the correct provider
			AppWidgetProviderInfo info = appWidgetManager.getAppWidgetInfo(appWidgetId);
			if (info != null) {
				String providerName = info.provider.getClassName();
				RemoteViews updateViews = null;

				if (providerName.equals(MedAppWidget.class.getName())) {
					updateViews = MedAppWidget.buildUpdate(this, appWidgetUri);
				} else if (providerName.equals(TinyAppWidget.class.getName())) {
					updateViews = TinyAppWidget.buildUpdate(this, appWidgetUri);
				} else if (providerName.equals(LargeAppWidget.class.getName())) {
					updateViews = LargeAppWidget.buildUpdate(this, appWidgetUri);
				}

				// Push this update to surface
				if (updateViews != null) {
					appWidgetManager.updateAppWidget(appWidgetId, updateViews);
				}
			}
		}

		// If auto schedule allowed, schedule next update alarm, usually just
		// before a x-hour block. This
		// triggers updates at roughly 5:50AM, 11:50AM, 5:50PM, and 11:50PM.
		if (update_interval > 0) {

			Time time = new Time();
			
			// if an update fail, request a faster update
			if (generalUpdateStatusOk)
				time.set(System.currentTimeMillis() + update_interval);
			else
				time.set(System.currentTimeMillis() + (update_interval / 10));
				
			long nextUpdate = time.toMillis(false);
			long nowMillis = System.currentTimeMillis();

			// Throttle our updates just in case the math went funky
			if (nextUpdate - nowMillis < UPDATE_THROTTLE) {
				Log.d(TAG, "Calculated next update too early, throttling for a few minutes");
				nextUpdate = nowMillis + UPDATE_THROTTLE;
			}

			long deltaMinutes = (nextUpdate - nowMillis) / DateUtils.MINUTE_IN_MILLIS;
			Log.d(TAG, "Requesting next update at " + nextUpdate + ", in " + deltaMinutes + " min");

			Intent updateIntent = new Intent(ACTION_UPDATE_ALL);
			updateIntent.setClass(this, UpdateService.class);

			PendingIntent pendingIntent = PendingIntent.getService(this, 0, updateIntent, 0);

			// Schedule alarm, and force the device awake for this update
			AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			alarmManager.set(AlarmManager.RTC_WAKEUP, nextUpdate, pendingIntent);

		}

		// No updates remaining, so stop service
		stopSelf();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private GeocodeQuery GeocoderGetData(GeocodeQuery query) {
		Geocoder mGeocoder = new Geocoder(this);

		GeocodeQuery result = null;
		int retries = 0;

		while ((result == null) && (retries < 3)) {
			try {
				if (!TextUtils.isEmpty(query.name)) {
					// Forward geocode using query
					List<Address> results = mGeocoder.getFromLocationName(query.name, 1);
					if (results.size() > 0) {
						result = new GeocodeQuery(results.get(0));
					}
				} else if (!Double.isNaN(query.lat) && !Double.isNaN(query.lon)) {
					// Reverse geocode using location
					List<Address> results = mGeocoder.getFromLocation(query.lat, query.lon, 1);
					if (results.size() > 0) {
						result = new GeocodeQuery(results.get(0));
						result.lat = query.lat;
						result.lon = query.lon;
					} else {
						result = query;
					}
				}
			} catch (IOException e) {
				Log.e(TAG, "Problem using geocoder", e);
			}

			retries++;
		}

		return result;
	}
}
