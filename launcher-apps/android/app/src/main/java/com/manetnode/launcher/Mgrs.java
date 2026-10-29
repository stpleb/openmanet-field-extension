package com.manetnode.launcher;

import java.util.Locale;

/** Lat/long (WGS84) to MGRS, 1 m precision, e.g. "14S PB 30681 19125". Polar areas (UPS) are not covered. */
final class Mgrs {
    private Mgrs() {}

    private static final String LAT_BANDS = "CDEFGHJKLMNPQRSTUVWX";
    private static final String[] COL_SETS = {"ABCDEFGH", "JKLMNPQR", "STUVWXYZ"};
    private static final String ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV";

    static String of(double lat, double lon) {
        if (lat < -80 || lat > 84 || Double.isNaN(lat) || Double.isNaN(lon)) return "—";
        lon = ((lon + 180) % 360 + 360) % 360 - 180;
        int zone = (int) Math.floor((lon + 180) / 6) + 1;
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32;                    // Norway
        if (lat >= 72) {                                                                   // Svalbard
            if (lon >= 0 && lon < 9) zone = 31;
            else if (lon >= 9 && lon < 21) zone = 33;
            else if (lon >= 21 && lon < 33) zone = 35;
            else if (lon >= 33 && lon < 42) zone = 37;
        }
        // Lat/long to UTM (Krueger series, WGS84)
        double a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996;
        double e2 = f * (2 - f), ep2 = e2 / (1 - e2);
        double phi = Math.toRadians(lat), lam = Math.toRadians(lon), lam0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double n = a / Math.sqrt(1 - e2 * Math.sin(phi) * Math.sin(phi));
        double t = Math.tan(phi) * Math.tan(phi), c = ep2 * Math.cos(phi) * Math.cos(phi), aa = Math.cos(phi) * (lam - lam0);
        double m = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi
                - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * Math.sin(2 * phi)
                + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * Math.sin(4 * phi)
                - (35 * e2 * e2 * e2 / 3072) * Math.sin(6 * phi));
        double easting = k0 * n * (aa + (1 - t + c) * aa * aa * aa / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * Math.pow(aa, 5) / 120) + 500000.0;
        double northing = k0 * (m + n * Math.tan(phi) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * Math.pow(aa, 4) / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * Math.pow(aa, 6) / 720));
        if (lat < 0) northing += 10000000.0;
        // UTM to MGRS
        char band = LAT_BANDS.charAt(Math.min(19, (int) Math.floor((lat + 80) / 8)));
        int e100k = (int) Math.floor(easting / 100000);
        char col = COL_SETS[(zone - 1) % 3].charAt(e100k - 1);
        int rowIdx = (int) Math.floor(northing / 100000) % 20;
        if (zone % 2 == 0) rowIdx = (rowIdx + 5) % 20;
        char row = ROW_LETTERS.charAt(rowIdx);
        int e = (int) Math.floor(easting % 100000), nn = (int) Math.floor(northing % 100000);
        return String.format(Locale.US, "%d%c %c%c %05d %05d", zone, band, col, row, e, nn);
    }
}
