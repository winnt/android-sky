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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jsharkey.sky.ForecastProvider.AppWidgets;
import org.jsharkey.sky.ForecastProvider.AppWidgetsColumns;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/**
 * Activity to configure forecast widgets. Usually launched automatically by an
 * {@link AppWidgetHost} after the {@link AppWidgetManager#EXTRA_APPWIDGET_ID}
 * has been bound to a widget.
 */
public class ConfigureActivity extends Activity implements View.OnClickListener, RadioGroup.OnCheckedChangeListener {

	public static final String TAG = "ConfigureActivity";

	private static final String[] PROJECTION_APPWIDGETS = new String[] { AppWidgetsColumns.UPDATE_FREQ,
			AppWidgetsColumns.CONFIGURED, AppWidgetsColumns.LAST_UPDATED, AppWidgetsColumns.UPDATE_LOCATION,
			AppWidgetsColumns.UPDATE_STATUS, AppWidgetsColumns.SKIN, AppWidgetsColumns.LANG, AppWidgetsColumns.ENCODING };

	private static final int COL_UPDATE_FREQ = 0;
	private static final int COL_CONFIGURED = 1;
	private static final int COL_LAST_UPDATED = 2;
	private static final int COL_UPDATE_LOCATION = 3;
	private static final int COL_UPDATE_STATUS = 4;
	private static final int COL_SKIN = 5;
	private static final int COL_LANG = 6;
	private static final int COL_ENCODING = 7;

	private String lang = "fr";
	private String encoding = "ISO-8859-1";
	private Integer updateFreq = 3;
	private double mLat = Double.NaN;
	private double mLon = Double.NaN;
	private Integer updateLocation = AppWidgetsColumns.UPDATE_LOCATION_FALSE;
	private Button mMap;
	private Button mSave;
	private EditText mTitle;
	private EditText mLang;
	private EditText mEncoding;
	private EditText mUpdateFreq;
	private EditText mSkinName;
	private Button mSkinSelectionBtn;

	// private List<String> skinList[] = new List<String>[];
	private ArrayList<String> skinList = new ArrayList<String>();

	/**
	 * Default zoom level when showing map to verify location.
	 */
	private static final int ZOOM_LEVEL = 10;

	/**
	 * Last found location fix, used when user selects "My current location."
	 */
	private Location mLastFix;

	private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

	private boolean widgetReconfiguration = false;

	/**
	 * Spawn a reverse geocoding operation to find names for the given
	 * {@link Location}. Will update GUI when finished.
	 */
	private void startGeocode(Location location) {
		new GeocoderTask().execute(new GeocodeQuery(location));
	}

	/**
	 * Spawn a forward geocoding operation to find the location of a given name.
	 * Will update GUI when finished.
	 */
	private void startGeocode(String query) {
		new GeocoderTask().execute(new GeocodeQuery(query));
	}

	/**
	 * Background task to perform a geocoding operation. Will disable GUI
	 * actions while running in the background, and then update GUI with results
	 * when found.
	 * <p>
	 * If no reverse geocoding results found, will still return original
	 * coordinates but will leave suggested title empty.
	 */
	private class GeocoderTask extends AsyncTask<GeocodeQuery, Void, GeocodeQuery> {
		private Geocoder mGeocoder;

		private GeocoderTask() {
			mGeocoder = new Geocoder(ConfigureActivity.this);
		}

		protected void onPreExecute() {
			// Show progress spinner and disable buttons
			setProgressBarIndeterminateVisibility(true);
			setActionEnabled(false);
		}

		protected GeocodeQuery doInBackground(GeocodeQuery... args) {
			GeocodeQuery query = args[0];
			GeocodeQuery result = null;

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

			return result;
		}

		protected void onPostExecute(GeocodeQuery found) {
			setProgressBarIndeterminateVisibility(false);

			// Update GUI with resolved string
			if (found == null) {
				mLat = Double.NaN;
				mLon = Double.NaN;
				setActionEnabled(false);
			} else {
				mTitle.setText(found.name);
				mLat = found.lat;
				mLon = found.lon;
				setActionEnabled(true);
			}
		}
	}

	/**
	 * Enable or disable any GUI actions, including text fields and buttons.
	 */
	protected void setActionEnabled(boolean enabled) {
		mTitle.setEnabled(enabled);
		mMap.setEnabled(enabled);
		mSave.setEnabled(enabled);
		mLang.setEnabled(enabled);
		mEncoding.setEnabled(enabled);
		mUpdateFreq.setEnabled(enabled);
		mSkinName.setEnabled(enabled);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

		setContentView(R.layout.configure);

		widgetReconfiguration = false;

		// build skin list
		skinList.add("-- default internal --");
		final String skinsPath = Environment.getExternalStorageDirectory() + "/" + this.getPackageName() + "/skins/";
		File folder = new File(skinsPath);
		if (folder.exists()) {
			File[] folderFileList = folder.listFiles();
			for (int i = 0; i < folderFileList.length; i++)
				if (folderFileList[i].isDirectory())
					skinList.add(folderFileList[i].getName());
		}

		mTitle = (EditText) findViewById(R.id.conf_title);

		mLang = (EditText) findViewById(R.id.conf_lang);
		mEncoding = (EditText) findViewById(R.id.conf_encoding);
		mUpdateFreq = (EditText) findViewById(R.id.conf_update_freq);
		mSkinName = (EditText) findViewById(R.id.conf_skin);
		mSkinSelectionBtn = (Button) findViewById(R.id.conf_select_skin);

		// set click listener
		((RadioButton) findViewById(R.id.conf_current_and_refreshed)).setOnClickListener(this);
		((RadioButton) findViewById(R.id.conf_manual)).setOnClickListener(this);
		((RadioButton) findViewById(R.id.conf_current)).setOnClickListener(this);

		((RadioButton) findViewById(R.id.conf_current)).setSelected(true);

		mMap = (Button) findViewById(R.id.conf_map);
		mSave = (Button) findViewById(R.id.conf_save);

		mMap.setOnClickListener(this);
		mSave.setOnClickListener(this);
		mSkinSelectionBtn.setOnClickListener(this);

		// Read the appWidgetId to configure from the incoming intent

		mAppWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
		if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {

			try {
				mAppWidgetId = Integer.parseInt(getIntent().getData().getLastPathSegment());
				widgetReconfiguration = true;
			} catch (Exception e) {
				Log.d(TAG, "impossible to get widget previous configuration");
			}
		} else {
			setConfigureResult(Activity.RESULT_CANCELED);
			finish();
			return;
		}

		// If restoring, read location from bundle
		if (savedInstanceState != null) {
			mLat = savedInstanceState.getDouble(AppWidgetsColumns.LAT);
			mLon = savedInstanceState.getDouble(AppWidgetsColumns.LON);
		}

		// set configured values
		if (widgetReconfiguration == true) {
			ContentResolver resolver = getContentResolver();
			Cursor cursor = null;
			try {
				cursor = resolver.query(getIntent().getData(), PROJECTION_APPWIDGETS, null, null, null);
				if (cursor != null && cursor.moveToFirst()) {

					updateFreq = cursor.getInt(COL_UPDATE_FREQ);
					updateLocation = cursor.getInt(COL_UPDATE_LOCATION);
					skinName = cursor.getString(COL_SKIN);
					lang = cursor.getString(COL_LANG);
					encoding = cursor.getString(COL_ENCODING);

				}
			} finally {
				if (cursor != null) {
					cursor.close();
				}
			}
		}

		mLang.setText(lang);
		mEncoding.setText(encoding);
		mUpdateFreq.setText(((Integer) updateFreq).toString());
		mSkinName.setText(skinName);

		// Start listener to find current location
		LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

		// check if network location provider is available
		LocationProvider provider = locationManager.getProvider(LocationManager.NETWORK_PROVIDER);

		if (provider != null) {

			locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10 * DateUtils.MINUTE_IN_MILLIS,
					100, // 1km
					locationListener);

			// Fire off geocoding request for last fix, but only if not
			// restoring
			mLastFix = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

			if (mLastFix != null && savedInstanceState == null) {
				startGeocode(mLastFix);
			}
		}

		if (mLastFix == null) {
			// No enabled providers found, so disable option
			RadioButton radioCurrent;
			radioCurrent = (RadioButton) findViewById(R.id.conf_current);
			radioCurrent.setEnabled(false);
			radioCurrent = (RadioButton) findViewById(R.id.conf_current_and_refreshed);
			radioCurrent.setEnabled(false);

			mTitle.setText(R.string.conf_nofix);
		}
	}

	private final LocationListener locationListener = new LocationListener() {
		public void onLocationChanged(Location location) {
			mLastFix = location;

			Log.d(TAG, "Location updated !!!!!!!!!!!!!! (" + mLastFix.getLatitude() + " - " + mLastFix.getLongitude()
					+ ")");
		}

		public void onProviderDisabled(String provider) {
		}

		public void onProviderEnabled(String provider) {
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
		}
	};

	private String skinName;

	/**
	 * Handle any new intents wrapping around from {@link SearchManager}.
	 */
	@Override
	public void onNewIntent(Intent intent) {
		final String action = intent.getAction();
		if (Intent.ACTION_SEARCH.equals(action)) {
			// Fire off geocoding request for given query
			String query = intent.getStringExtra(SearchManager.QUERY);
			startGeocode(query);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		lang = mLang.getText().toString();
		encoding = mEncoding.getText().toString();

		try {
			updateFreq = Integer.parseInt(mUpdateFreq.getText().toString());
		} catch (Exception e) {
			mUpdateFreq.setText("3");
			updateFreq = 3;
		}

		outState.putDouble(AppWidgetsColumns.LAT, mLat);
		outState.putDouble(AppWidgetsColumns.LON, mLon);
		outState.putString(AppWidgetsColumns.LANG, lang);
		outState.putString(AppWidgetsColumns.ENCODING, encoding);
		outState.putInt(AppWidgetsColumns.UPDATE_FREQ, updateFreq);
	}

	public void onClick(View v) {
		switch (v.getId()) {
		case R.id.conf_current: {
			// Picked current location, start geocode to find location name
			startGeocode(mLastFix);
			updateLocation = AppWidgetsColumns.UPDATE_LOCATION_FALSE;
			break;
		}
		case R.id.conf_current_and_refreshed: {
			// Picked current location, start geocode to find location name
			startGeocode(mLastFix);
			updateLocation = AppWidgetsColumns.UPDATE_LOCATION_TRUE;
			break;
		}
		case R.id.conf_manual: {
			// Picked manual search, so trigger search dialog
			onSearchRequested();
			updateLocation = AppWidgetsColumns.UPDATE_LOCATION_FALSE;
			break;
		}
		case R.id.conf_map: {
			// Picked verify on map, so launch mapping intent
			Uri mapUri = Uri.parse(String.format("geo:%f,%f?z=%d", mLat, mLon, ZOOM_LEVEL));

			Intent mapIntent = new Intent(Intent.ACTION_VIEW);
			mapIntent.setData(mapUri);

			startActivity(mapIntent);
			break;
		}
		case R.id.conf_select_skin: {

			// show skin list
			String[] skinListTab = (String[]) skinList.toArray(new String[skinList.size()]);

			new AlertDialog.Builder(ConfigureActivity.this).setTitle(
					getResources().getString(R.string.conf_skin_select_btn)).setItems(skinListTab,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int whichSkin) {
							// if not default skin, set skin name
							if (whichSkin > 0) {
								skinName = skinList.get(whichSkin);
								mSkinName.setText(skinName);
							}
						}
					}).show();
			break;
		}
		case R.id.conf_save: {
			// Picked save, so write values to backend
			String title = mTitle.getText().toString();
			lang = mLang.getText().toString();
			encoding = mEncoding.getText().toString();
			updateFreq = Integer.parseInt(mUpdateFreq.getText().toString());
			skinName = mSkinName.getText().toString();

			// check skin validity
			final String skinsPath = Environment.getExternalStorageDirectory() + "/" + this.getPackageName()
					+ "/skins/";
			if (!skinName.equals("")) {
				File f = new File(skinsPath + skinName, ".");
				if (!f.exists()) {
					new AlertDialog.Builder(ConfigureActivity.this).setTitle(
							getResources().getString(R.string.conf_skin_check_title)).setMessage(
							getResources().getString(R.string.conf_skin_check_message)).setNeutralButton("Ok",
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int whichButton) {
								}
							}).show();
					break;
				}
			}

			ContentValues values = new ContentValues();

			values.put(BaseColumns._ID, mAppWidgetId);
			values.put(AppWidgetsColumns.TITLE, title);
			values.put(AppWidgetsColumns.LAT, mLat);
			values.put(AppWidgetsColumns.LON, mLon);
			values.put(AppWidgetsColumns.LANG, lang);
			values.put(AppWidgetsColumns.ENCODING, encoding);
			values.put(AppWidgetsColumns.UPDATE_FREQ, updateFreq);
			values.put(AppWidgetsColumns.UPDATE_LOCATION, updateLocation);
			values.put(AppWidgetsColumns.SKIN, skinName);
			values.put(AppWidgetsColumns.LAST_UPDATED, -1);
			values.put(AppWidgetsColumns.CONFIGURED, AppWidgetsColumns.CONFIGURED_TRUE);

			// update instead of insert if editing an existing widget
			ContentResolver resolver = getContentResolver();
			if (!widgetReconfiguration)
				resolver.insert(AppWidgets.CONTENT_URI, values);
			else
				//Uri.withAppendedPath(AppWidgets.CONTENT_URI
				resolver.update(getIntent().getData(), values, null, null);

			// Trigger pushing a widget update to surface
			UpdateService.requestUpdate(new int[] { mAppWidgetId });
			ComponentName updateService = startService(new Intent(this, UpdateService.class));

			if (null == updateService) {
				// something really wrong here
				Log.e(TAG, "Could not start location service");
			}

			setConfigureResult(Activity.RESULT_OK);
			finish();

			break;
		}
		}
	}

	/**
	 * Change {@link #mUnits} when requested by user.
	 */
	public void onCheckedChanged(RadioGroup group, int checkedId) {
		switch (checkedId) {
		default:
			break;
		}
	}

	/**
	 * Convenience method to always include {@link #mAppWidgetId} when setting
	 * the result {@link Intent}.
	 */
	public void setConfigureResult(int resultCode) {
		final Intent data = new Intent();
		data.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
		setResult(resultCode, data);
	}
}
