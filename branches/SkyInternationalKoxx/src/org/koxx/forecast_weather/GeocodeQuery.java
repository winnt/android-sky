package org.koxx.forecast_weather;

import android.location.Address;
import android.location.Location;

public class GeocodeQuery {

	String name = null;

	double lat = Double.NaN;
	double lon = Double.NaN;

	public GeocodeQuery(String query) {
		name = query;
	}

	public GeocodeQuery(Location location) {
		lat = location.getLatitude();
		lon = location.getLongitude();
	}

	/**
	 * Summarize details of the given {@link Address}, walking down a
	 * prioritized list of names until valid text is found to describe it.
	 */
	public GeocodeQuery(Address address) {
		name = address.getLocality();
		if (name == null) {
			name = address.getFeatureName();
		}
		if (name == null) {
			name = address.getAdminArea();
		}
		if (name == null) {
			name = address.getPostalCode();
		}
		if (name == null) {
			name = address.getCountryName();
		}

		// Fill in coordinates, if given
		if (address.hasLatitude() && address.hasLongitude()) {
			lat = address.getLatitude();
			lon = address.getLongitude();
		}
	}
}
