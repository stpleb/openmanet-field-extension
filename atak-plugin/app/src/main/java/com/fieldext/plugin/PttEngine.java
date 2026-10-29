package com.fieldext.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Push-to-talk in OpenMANET's own comms format, so the phone talks with OpenMANET nodes, other phones
 * and the ATAK Voice plugin: Opus 48 kHz mono, 20 ms frames, 32 kbit/s, RTP payload type 111, sent to the
 * talk group's multicast address and port (talk group 1 = port 38801). Half-duplex like OpenMANET:
 * while someone else is talking the channel is busy.
 */
final class PttEngine {
    static final int RATE = 48000, FRAME = 960, PT_OPUS = 111, BITRATE = 32000, TTL = 32;
    static final int[] GROUP_PORTS = {38801, 38803, 38805, 38807, 38809};
    private static final long BUSY_MS = 600;
    private static final String PREFS = "ptt", K_ADDR = "address", K_GROUP = "group";

    enum State { OFF, READY, TALKING, RECEIVING, BUSY }

    interface Listener { void changed(); }

    private static PttEngine engine;

    static synchronized PttEngine get(Context ctx) {
        if (engine == null) engine = new PttEngine(ctx.getApplicationContext());
        return engine;
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final int ssrc = new SecureRandom().nextInt();
    private volatile long lastRx;
    private final long[] lastRxGroup = new long[GROUP_PORTS.length];
    private volatile int lastRxGroupNo;
    private volatile boolean talking;
    private volatile String error;
    private Thread rxThread;
    private volatile int rxGeneration;

    private PttEngine(Context ctx) { this.ctx = ctx; }

    // ------------------------------------------------------------------ settings
    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Talk-group multicast address from the node's comms config; empty = not set yet. */
    /** Default for phones on a mesh; set it to the node's talk group 1 address to talk with OpenMANET nodes. */
    static final String DEFAULT_ADDR = "239.41.0.1";

    String address() { return prefs().getString(K_ADDR, DEFAULT_ADDR).trim(); }

    int group() { return Math.max(1, Math.min(GROUP_PORTS.length, prefs().getInt(K_GROUP, 1))); }

    int port() { return GROUP_PORTS[group() - 1]; }

    /** Talk groups this phone listens to (talk group 1 by default). */
    boolean listening(int g) { return prefs().getBoolean("rx" + g, g == 1); }

    void setListening(int g, boolean on) {
        prefs().edit().putBoolean("rx" + g, on).apply();
        restartRx();
        changed();
    }

    /** Talk on this group (also turns listening on for it). */
    void setTalkGroup(int g) {
        prefs().edit().putInt(K_GROUP, g).putBoolean("rx" + g, true).apply();
        restartRx();
        changed();
    }

    /** True while audio arrived on group g in the last moment (for the channel list). */
    boolean active(int g) { return System.currentTimeMillis() - lastRxGroup[g - 1] < BUSY_MS; }

    int lastReceivedGroup() { return lastRxGroupNo; }

    void configure(String address, int group) {
        prefs().edit().putString(K_ADDR, address.trim()).putInt(K_GROUP, group).apply();
        restartRx();
        changed();
    }

    String error() { return error; }

    /** Phone audio off = the phone stays quiet (another radio or headset handles audio). */
    boolean audioOn() { return prefs().getBoolean("audio", true); }

    void setAudioOn(boolean on) { prefs().edit().putBoolean("audio", on).apply(); changed(); }

    State state() {
        if (address().isEmpty()) return State.OFF;
        if (talking) return State.TALKING;
        if (System.currentTimeMillis() - lastRx < BUSY_MS) return State.RECEIVING;
        return State.READY;
    }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() { ui.post(() -> { for (Listener l : listeners) l.changed(); }); }

    private static NetworkInterface wifi() {
        try {
            for (String n : new String[]{"wlan0", "wlan1"}) {
                NetworkInterface i = NetworkInterface.getByName(n);
                if (i != null && i.isUp()) return i;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ receive
    synchronized void start() {
        if (rxThread == null) restartRx();
    }

    private synchronized void restartRx() {
        final int gen = ++rxGeneration;
        final String addr = address();
        if (addr.isEmpty()) { rxThread = null; return; }
        for (int g = 1; g <= GROUP_PORTS.length; g++) {
            if (!listening(g)) continue;
            final int grp = g;
            rxThread = new Thread(() -> receive(gen, addr, GROUP_PORTS[grp - 1], grp), "ptt-rx-" + g);
            rxThread.setDaemon(true);
            rxThread.start();
        }
    }

    private void receive(int gen, String addr, int port, int grp) {
        while (gen == rxGeneration) {
            MediaCodec dec = null;
            AudioTrack out = null;
            try (MulticastSocket s = new MulticastSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
                s.setSoTimeout(1000);
                NetworkInterface ni = wifi();
                InetSocketAddress g = new InetSocketAddress(InetAddress.getByName(addr), port);
                if (ni != null) s.joinGroup(g, ni); else s.joinGroup(InetAddress.getByName(addr));
                dec = opusDecoder();
                out = player();
                out.play();
                error = null;
                byte[] buf = new byte[2048];
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean wasBusy = false;
                while (gen == rxGeneration) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (java.net.SocketTimeoutException t) {
                        if (wasBusy && System.currentTimeMillis() - lastRx > BUSY_MS) { wasBusy = false; changed(); }
                        continue;
                    }
                    int off = rtpPayload(p.getData(), p.getLength());
                    if (off < 0) continue;
                    int pktSsrc = ByteBuffer.wrap(p.getData(), 8, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                    if (pktSsrc == ssrc || talking) continue;          // our own, or we hold the floor
                    lastRx = System.currentTimeMillis();
                    lastRxGroup[grp - 1] = lastRx;
                    lastRxGroupNo = grp;
                    if (!wasBusy) { wasBusy = true; changed(); }
                    if (audioOn()) decode(dec, out, info, p.getData(), off, p.getLength() - off);
                }
            } catch (Exception e) {
                error = "PTT: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                changed();
                try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
            } finally {
                if (dec != null) try { dec.stop(); dec.release(); } catch (Exception ignored) { }
                if (out != null) try { out.stop(); out.release(); } catch (Exception ignored) { }
            }
        }
    }

    /** Offset of the Opus payload in an RTP packet, or -1 if it is not RTP payload type 111. */
    static int rtpPayload(byte[] b, int len) {
        if (len < 13 || (b[0] & 0xC0) != 0x80 || (b[1] & 0x7F) != PT_OPUS) return -1;
        int off = 12 + 4 * (b[0] & 0x0F);
        if ((b[0] & 0x10) != 0) {
            if (len < off + 4) return -1;
            off += 4 + 4 * (((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF));
        }
        int pad = (b[0] & 0x20) != 0 ? (b[len - 1] & 0xFF) : 0;
        return off < len - pad ? off : -1;
    }

    private static MediaCodec opusDecoder() throws Exception {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1);
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);   // OpusHead
        head.put("OpusHead".getBytes()).put((byte) 1).put((byte) 1).putShort((short) 312).putInt(RATE)
                .putShort((short) 0).put((byte) 0);
        head.flip();
        f.setByteBuffer("csd-0", head);
        f.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, 6500000L));
        f.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0, 80000000L));
        MediaCodec d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        d.configure(f, null, null, 0);
        d.start();
        return d;
    }

    private static AudioTrack player() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, RATE / 5 * 2))      // ~200 ms of jitter room
                .setTransferMode(AudioTrack.MODE_STREAM).build();
    }

    private static void decode(MediaCodec dec, AudioTrack out, MediaCodec.BufferInfo info, byte[] data, int off, int len) {
        int in = dec.dequeueInputBuffer(20000);
        if (in >= 0) {
            ByteBuffer ib = dec.getInputBuffer(in);
            ib.clear();
            ib.put(data, off, len);
            dec.queueInputBuffer(in, 0, len, System.nanoTime() / 1000, 0);
        }
        int o;
        while ((o = dec.dequeueOutputBuffer(info, 0)) >= 0) {
            ByteBuffer ob = dec.getOutputBuffer(o);
            byte[] pcm = new byte[info.size];
            ob.position(info.offset);
            ob.get(pcm);
            out.write(pcm, 0, pcm.length);
            dec.releaseOutputBuffer(o, false);
        }
    }

    // ------------------------------------------------------------------ transmit
    /** Press: returns false (and why, in error()) if the channel is busy or PTT is not set up. */
    @SuppressLint("MissingPermission")
    synchronized boolean pressed() {
        if (talking) return true;
        if (address().isEmpty()) { error = "Set the talk group in Settings first"; changed(); return false; }
        if (System.currentTimeMillis() - lastRxGroup[group() - 1] < BUSY_MS) { error = "Channel busy"; changed(); return false; }
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            error = "Allow the microphone for ManetNode";
            changed();
            return false;
        }
        talking = true;
        error = null;
        final String addr = address();
        final int port = port();
        Thread t = new Thread(() -> transmit(addr, port), "ptt-tx");
        t.start();
        changed();
        return true;
    }

    void released() {
        talking = false;
        changed();
    }

    @SuppressLint("MissingPermission")
    private void transmit(String addr, int port) {
        AudioRecord mic = null;
        MediaCodec enc = null;
        try (MulticastSocket s = new MulticastSocket()) {
            NetworkInterface ni = wifi();
            if (ni != null) s.setNetworkInterface(ni);
            s.setTimeToLive(TTL);
            s.setLoopbackMode(true);                                  // true = loopback disabled
            InetAddress group = InetAddress.getByName(addr);
            int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, FRAME * 2 * 4));
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1);
            f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mic.startRecording();
            byte[] pcm = new byte[FRAME * 2];
            byte[] pkt = new byte[1500];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int seq = new SecureRandom().nextInt(0xFFFF);
            long ts = new SecureRandom().nextInt() & 0xFFFFFFFFL;
            boolean first = true;
            while (talking) {
                int n = 0;
                while (n < pcm.length && talking) {
                    int r = mic.read(pcm, n, pcm.length - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n < pcm.length) break;
                int in = enc.dequeueInputBuffer(20000);
                if (in >= 0) {
                    ByteBuffer ib = enc.getInputBuffer(in);
                    ib.clear();
                    ib.put(pcm);
                    enc.queueInputBuffer(in, 0, pcm.length, System.nanoTime() / 1000, 0);
                }
                int o;
                while ((o = enc.dequeueOutputBuffer(info, 0)) >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && info.size < pkt.length - 12) {
                        ByteBuffer ob = enc.getOutputBuffer(o);
                        ob.position(info.offset);
                        ob.get(pkt, 12, info.size);
                        pkt[0] = (byte) 0x80;
                        pkt[1] = (byte) ((first ? 0x80 : 0) | PT_OPUS);
                        pkt[2] = (byte) (seq >> 8);
                        pkt[3] = (byte) seq;
                        pkt[4] = (byte) (ts >> 24);
                        pkt[5] = (byte) (ts >> 16);
                        pkt[6] = (byte) (ts >> 8);
                        pkt[7] = (byte) ts;
                        pkt[8] = (byte) (ssrc >> 24);
                        pkt[9] = (byte) (ssrc >> 16);
                        pkt[10] = (byte) (ssrc >> 8);
                        pkt[11] = (byte) ssrc;
                        s.send(new DatagramPacket(pkt, 12 + info.size, group, port));
                        first = false;
                        seq = (seq + 1) & 0xFFFF;
                        ts = (ts + FRAME) & 0xFFFFFFFFL;
                    }
                    enc.releaseOutputBuffer(o, false);
                }
            }
        } catch (Exception e) {
            error = "PTT send failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            talking = false;
            if (mic != null) try { mic.stop(); mic.release(); } catch (Exception ignored) { }
            if (enc != null) try { enc.stop(); enc.release(); } catch (Exception ignored) { }
            changed();
        }
    }
}
