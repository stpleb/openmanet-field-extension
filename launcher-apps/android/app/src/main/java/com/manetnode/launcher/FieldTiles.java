package com.manetnode.launcher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.modules.CantContinueException;
import org.osmdroid.tileprovider.modules.IFilesystemCache;
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck;
import org.osmdroid.tileprovider.modules.MapTileDownloader;
import org.osmdroid.tileprovider.modules.TileDownloader;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Map tiles over whichever connection has internet. On a node's Wi-Fi (no internet) Android would
 * send tile requests to the node and get nothing; this fetches them over cellular instead, while
 * node traffic stays on Wi-Fi. Tiles are cached on the phone, so the map also works offline later.
 */
final class FieldTiles extends MapTileProviderBasic {
    private final Context ctx;

    FieldTiles(Context ctx) {
        super(ctx, TileSourceFactory.MAPNIK);
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    protected MapTileDownloader createDownloaderProvider(INetworkAvailablityCheck check, ITileSource source) {
        INetworkAvailablityCheck any = new INetworkAvailablityCheck() {      // "online" = some network reaches the internet
            @Override public boolean getNetworkAvailable() { return internet(ctxOr()) != null; }
            @Override public boolean getWiFiNetworkAvailable() { return getNetworkAvailable(); }
            @Override public boolean getCellularDataNetworkAvailable() { return getNetworkAvailable(); }
            @Override public boolean getRouteToPathExists(int host) { return getNetworkAvailable(); }
        };
        MapTileDownloader d = super.createDownloaderProvider(any, source);
        d.setTileDownloader(new Downloader());
        return d;
    }

    private Context ctxOr() { return ctx; }

    /** A network that really reaches the internet (validated), preferring one that is not the node Wi-Fi. */
    static Network internet(Context ctx) {
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network wifi = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            wifi = n;
        }
        return wifi;
    }

    private final class Downloader extends TileDownloader {
        @Override
        public Drawable downloadTile(long index, int redirects, String url, IFilesystemCache cache,
                                     OnlineTileSourceBase source) throws CantContinueException {
            Network net = internet(ctx);
            if (net == null) return null;                                  // offline: cached tiles only
            try {
                HttpURLConnection c = (HttpURLConnection) net.openConnection(new URL(url));
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty(Configuration.getInstance().getUserAgentHttpHeader(),
                        Configuration.getInstance().getUserAgentValue());
                try {
                    if (c.getResponseCode() != 200) return null;
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] chunk = new byte[16384];
                        int n;
                        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    }
                    byte[] data = buf.toByteArray();
                    if (cache != null)
                        cache.saveFile(source, index, new ByteArrayInputStream(data),
                                System.currentTimeMillis() + 30L * 24 * 3600 * 1000);
                    return source.getDrawable(new ByteArrayInputStream(data));
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
        }
    }
}
