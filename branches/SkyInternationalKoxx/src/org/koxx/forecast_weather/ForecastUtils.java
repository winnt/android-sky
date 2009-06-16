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

package org.koxx.forecast_weather;

import java.util.regex.Pattern;

import org.koxx.forecast_weather.R;
import org.koxx.forecast_weather.ForecastProvider.ForecastsColumns;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Environment;
import android.text.format.Time;

/**
 * Various forecast utilities.
 */
public class ForecastUtils {

	/**
	 * Time when we consider daytime to begin. We keep this early to make sure
	 * that our 6AM widget update will change icons correctly.
	 */
	private static final int DAYTIME_BEGIN_HOUR = 7;

	/**
	 * Time when we consider daytime to end. We keep this early to make sure
	 * that our 6PM widget update will change icons correctly.
	 */
	private static final int DAYTIME_END_HOUR = 20;

	private static final Pattern sIconAlert = Pattern.compile("alert|advisory|warning|watch|dust|smoke",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconStorm = Pattern.compile("thunderstorm|storm|chance_of_storm",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconSnow = Pattern.compile("chance_of_snow|snow|frost|flurries|sleet",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconIcy = Pattern.compile("icy", Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconShower = Pattern.compile("rain", Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconScatter = Pattern.compile("chance_of_rain|mist", Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconClear = Pattern.compile("sunny|clear|mostly_sunny", Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconFewClouds = Pattern.compile("partly_cloudy", Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconBigClouds = Pattern.compile("cloudy|mostly_cloudy", Pattern.CASE_INSENSITIVE);
	private static final Pattern sIconHazeAlert = Pattern.compile("haze|fog", Pattern.CASE_INSENSITIVE);

	/**
	 * Select an icon to describe the given {@link ForecastsColumns#CONDITIONS}
	 * string. Uses a descending importance scale that matches keywords against
	 * the described conditions.
	 * 
	 * @param daytime
	 *            If true, return daylight-specific icons when available,
	 *            otherwise assume night icons.
	 */

	public static Bitmap getIconBitmapForForecast(Context context, String icon_url, boolean daytime, boolean useSkin,
			String skinName) {

		Bitmap icon;

		if (useSkin) {
			String bitmapResourcePath = getIconBitmapForForecast(icon_url, daytime);
			BitmapDrawable iconBitmap = new BitmapDrawable(Environment.getExternalStorageDirectory() + "/"
					+ context.getPackageName() + "/skins/" + skinName + "/" + bitmapResourcePath);
			icon = iconBitmap.getBitmap();
		} else {
			icon = BitmapFactory.decodeResource(context.getResources(), getInternalIconForForecast(icon_url, daytime));
		}
		return icon;
	}

	/*
	 * google icons to bitmap
	 */
	public static String getIconBitmapForForecast(String icon_url, boolean daytime) {

		String conditionsSplit[] = icon_url.split("/");
		String iconName = conditionsSplit[conditionsSplit.length - 1].replace(".png", "");
		iconName = iconName.replace(".gif", ".png");

		return iconName;
	}

	/*
	 * google icons to drawable
	 */

	public static int getInternalIconForForecast(String icon_url, boolean daytime) {
		int iconResId = 0;

		String conditionsSplit[] = icon_url.split("/");
		String conditions = conditionsSplit[conditionsSplit.length - 1].replace(".gif", "");

		// images/weather/dust.gif ----------- sIconAlert
		// images/weather/smoke.gif ----------- sIconAlert
		if (sIconAlert.matcher(conditions).find()) {
			iconResId = R.drawable.weather_severe_alert;
		} else
		// images/weather/haze.gif ----------- sIconHazeAlert
		// images/weather/fog.gif ----------- sIconHazeAlert
		if (sIconHazeAlert.matcher(conditions).find()) {
			iconResId = R.drawable.weather_haze_alert;
		} else
		// images/weather/partly_cloudy.gif ----------- sIconFewClouds
		if (sIconFewClouds.matcher(conditions).find()) {
			iconResId = daytime ? R.drawable.weather_few_clouds : R.drawable.weather_few_clouds_night;
		} else
		// images/weather/cloudy.gif ----------- sIconBigClouds
		// images/weather/mostly_cloudy.gif ----------- sIconBigClouds
		if (sIconBigClouds.matcher(conditions).find()) {
			iconResId = daytime ? R.drawable.weather_big_clouds : R.drawable.weather_few_clouds_night;
		} else
		// images/weather/icy.gif ----------- sIconIcy
		if (sIconIcy.matcher(conditions).find()) {
			iconResId = R.drawable.weather_snow_icy;
		} else
		// images/weather/chance_of_storm.gif ----------- sIconStorm
		// images/weather/storm.gif ----------- sIconStorm
		// images/weather/thunderstorm.gif ----------- sIconStorm
		if (sIconStorm.matcher(conditions).find()) {
			iconResId = R.drawable.weather_storm;
		} else
		// images/weather/chance_of_snow.gif ----------- sIconSnow
		// images/weather/snow.gif ----------- sIconSnow
		// images/weather/sleet.gif ----------- sIconSnow
		if (sIconSnow.matcher(conditions).find()) {
			iconResId = R.drawable.weather_snow;

		} else
		// images/weather/chance_of_rain.gif ----------- sIconScatter
		// images/weather/mist.gif ----------- sIconScatter
		if (sIconScatter.matcher(conditions).find()) {
			iconResId = R.drawable.weather_showers_scattered;
		} else
		// images/weather/rain.gif ----------- sIconShower
		if (sIconShower.matcher(conditions).find()) {
			iconResId = R.drawable.weather_showers;
		} else
		// images/weather/sunny.gif ----------- sIconClear
		// images/weather/mostly_sunny.gif ----------- sIconClear
		if (sIconClear.matcher(conditions).find()) {
			iconResId = daytime ? R.drawable.weather_clear : R.drawable.weather_clear_night;
		}

		return iconResId;
	}

	/**
	 * Get the timestamp of the last midnight, in a base similar to
	 * {@link System#currentTimeMillis()}.
	 */
	public static long getLastMidnight() {
		Time time = new Time();
		time.setToNow();
		time.hour = 0;
		time.minute = 0;
		time.second = 0;
		return time.toMillis(false);
	}

	/**
	 * Calcuate if it's currently "daytime" by our internal definition. Used to
	 * decide which icons to show when updating widgets.
	 */
	public static boolean isDaytime() {
		Time time = new Time();
		time.setToNow();
		return (time.hour >= DAYTIME_BEGIN_HOUR && time.hour <= DAYTIME_END_HOUR);
	}
}
