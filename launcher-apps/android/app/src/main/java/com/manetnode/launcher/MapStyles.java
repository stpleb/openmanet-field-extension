package com.manetnode.launcher;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.MapTileIndex;

/**
 * Map styles: dark street map (OpenStreetMap, default), USGS satellite, satellite with labels, and dark
 * USGS topo (US public domain, no key needed). Each style caches its tiles separately for offline use.
 */
final class MapStyles {
    static final String[] NAMES = {"Street", "Satellite", "Hybrid", "Topo"};

    private MapStyles() {}

    private static final class Usgs extends OnlineTileSourceBase {
        Usgs(String name, String service, int maxZoom) {
            super(name, 0, maxZoom, 256, "",
                    new String[]{"https://basemap.nationalmap.gov/arcgis/rest/services/" + service + "/MapServer/tile/"},
                    "USGS The National Map");
        }

        @Override
        public String getTileURLString(long index) {
            return getBaseUrl() + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index);
        }
    }

    private static final ITileSource[] SOURCES = {
            TileSourceFactory.MAPNIK,                       // Street: dark, with the filter below
            new Usgs("USGS-Imagery", "USGSImageryOnly", 16),
            new Usgs("USGS-ImageryTopo", "USGSImageryTopo", 16),
            new Usgs("USGS-Topo", "USGSTopo", 16),
    };

    /**
     * Street and topo maps in dark mode, like Google's: invert brightness, rotate hue 180 deg so colours keep their
     * meaning (water dark blue, parks dark green), then soften and tint slightly blue-grey.
     */
    /** Street and Topo have a dark mode; Satellite and Hybrid are photos. */
    static boolean canDarken(int style) { return style == 0 || style == 3; }

    static android.graphics.ColorFilter filter(int style, boolean dark) {
        if (!dark || !canDarken(style)) return null;   // Street and Topo can be dark; satellite stays as is
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        m.postConcat(new android.graphics.ColorMatrix(new float[]{     // hue rotate 180
                -0.574f, 1.430f, 0.144f, 0, 0,
                0.426f, 0.430f, 0.144f, 0, 0,
                0.426f, 1.430f, -0.856f, 0, 0,
                0, 0, 0, 1, 0}));
        android.graphics.ColorMatrix sat = new android.graphics.ColorMatrix();
        sat.setSaturation(0.55f);
        m.postConcat(sat);
        m.postConcat(new android.graphics.ColorMatrix(new float[]{     // darker, slight blue-grey
                0.78f, 0, 0, 0, 8,
                0, 0.80f, 0, 0, 12,
                0, 0, 0.86f, 0, 22,
                0, 0, 0, 1, 0}));
        return new android.graphics.ColorMatrixColorFilter(m);
    }

    static ITileSource source(int style) { return SOURCES[Math.max(0, Math.min(SOURCES.length - 1, style))]; }
}
