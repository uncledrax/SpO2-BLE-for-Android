package com.berry_med.bci.blutooth;

import java.text.DecimalFormat;
import java.util.Arrays;

/*
 * description: ParseRunnable - decodes the oximeter byte stream.
 *
 * Both wire formats of the "BCI & BERRY Protocol V1.0" are recognised automatically, whatever the
 * protocol radio button says (that only chooses what the screen shows):
 *   BCI   5-byte packets, head byte has bit 7 set, the other four bytes have it clear
 *   BERRY 20-byte frames, head FF AA, checksum in byte 19
 * Version replies (BCI: FF/FE + ASCII; BERRY: FF AA 'S'/'H' + ASCII) are recognised as packets.
 *
 * author: zl (original), rework for logging 2026
 */
public class ParseRunnable implements Runnable {
    protected OnDataChangeListener mOnDataChangeListener;
    private RawSink rawSink;

    private final Object lock = new Object();
    private byte[] buf = new byte[8192];
    private int len = 0;
    private volatile long lastChunkMs = 0;

    private int berryLock = 0;         // > 0: inside a BERRY stream, do not read stray bytes as BCI packets
    private String currentProto = "";
    public int badChecksum = 0;

    boolean isStop = false;

    public ParseRunnable(OnDataChangeListener onDataChangeListener) {
        isStop = true;
        this.mOnDataChangeListener = onDataChangeListener;
    }

    public void setStop(boolean stop) {
        isStop = stop;
    }

    public void init() {
        synchronized (lock) {
            len = 0;
        }
        berryLock = 0;
        currentProto = "";
        verMore = 0;
    }

    /** Receives every BLE notification as-is (for the raw hex dump). */
    public interface RawSink {
        void raw(long hostMs, byte[] data);
    }

    public void setRawSink(RawSink sink) {
        rawSink = sink;
    }

    /**
     * interface for parameters changed.
     */
    public interface OnDataChangeListener {

        void deviceInfo(String name, String mac);

        void value(int spo2, int rr, int pr, double pi, int resp, int wave, int packetFreq);

        void hardwareVersion(String v);

        void softwareVersion(String v);

        /**
         * One call per device packet (100/200 Hz). hostMs = phone clock when the BLE chunk arrived.
         * BCI: devIdx/spo2Real/prReal/rrMs/piReal/adc/battery are -1, pi = signal strength (0-8),
         * freq = bargraph (0-15), status bits: 1 probe unplugged, 2 no finger, 4 no signal/searching, 8 beep.
         * BERRY: fields as in the protocol document; status: 1 sensor off, 2 no finger, 4 no pulse, 8 beat.
         */
        void packet(long hostMs, String proto, int devIdx, int status, int spo2, int spo2Real, int pr, int prReal,
                    int rrMs, double pi, double piReal, int wave, long adc, int battery, int freq);

        /** The framing actually arriving changed: "BCI" or "BERRY". */
        void protocol(String proto);
    }

    /**
     * Add the data from Bluetooth Device to the buffer.
     *
     * @param data data from Bluetooth Device
     */
    public void add(byte[] data) {
        if (data == null || data.length == 0) return;
        long now = System.currentTimeMillis();
        lastChunkMs = now;
        if (rawSink != null) rawSink.raw(now, data);
        synchronized (lock) {
            if (len + data.length > buf.length) {
                if (len + data.length > 1 << 20) len = 0;          // parser stalled: drop the backlog
                else buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + data.length));
            }
            System.arraycopy(data, 0, buf, len, data.length);
            len += data.length;
            lock.notifyAll();
        }
    }

    /**
     * Parsing the protocol as the manual.
     */
    @Override
    public void run() {
        int[] frame = new int[20];
        byte[] work;
        int n;
        while (isStop) {
            synchronized (lock) {
                try {
                    while (len < 5 && isStop) lock.wait(250);
                } catch (InterruptedException e) {
                    return;
                }
                n = len;
                work = Arrays.copyOf(buf, n);
            }
            int pos = 0;
            while (n - pos >= 5) {
                int b0 = work[pos] & 0xFF;
                if (b0 == 0xFF && (work[pos + 1] & 0xFF) == 0xAA) {            // BERRY 20-byte frame
                    if (n - pos < 20) break;                                    // wait for the rest
                    int sum = 0;
                    for (int i = 0; i < 19; i++) sum += work[pos + i] & 0xFF;
                    if ((sum & 0xFF) == (work[pos + 19] & 0xFF)) {
                        for (int i = 0; i < 20; i++) frame[i] = work[pos + i] & 0xFF;
                        berryFrame(frame);
                        pos += 20;
                        berryLock = 400;                                        // ~20 frames of slack
                    } else {
                        badChecksum++;
                        pos++;
                    }
                    continue;
                }
                if (berryLock > 0) {                                            // stray bytes inside a BERRY stream
                    berryLock--;
                    pos++;
                    continue;
                }
                if ((b0 & 0x80) == 0) {                                         // not a BCI head
                    pos++;
                    continue;
                }
                boolean inner = false;
                for (int i = 1; i < 5; i++) if ((work[pos + i] & 0x80) != 0) inner = true;
                if (inner) {                                                    // a head inside: truncated packet
                    pos++;
                    continue;
                }
                for (int i = 0; i < 5; i++) frame[i] = work[pos + i] & 0xFF;
                bciPacket(frame);
                pos += 5;
            }
            synchronized (lock) {                                               // drop what was consumed
                if (pos > 0) {
                    System.arraycopy(buf, pos, buf, 0, len - pos);
                    len -= pos;
                }
            }
        }
    }

    private void protoSeen(String p) {
        if (!p.equals(currentProto)) {
            currentProto = p;
            mOnDataChangeListener.protocol(p);
        }
    }

    // ---------------------------------------------------------------- BCI
    private void bciPacket(int[] data) {
        if (bciVersion(data)) return;
        verMore = 0;
        protoSeen("BCI");
        int beep = data[0];
        int wave = data[1];
        int spo2 = data[4];
        int pr = data[3] | ((data[2] & 0x40) << 1);
        int resp = 0;
        double pi;
        if (Model.MODEL.equals("BCI-RESP")) {
            pi = _calculatePi(beep, data[2]);
        } else {
            pi = data[0] & 0x0F;
        }
        mOnDataChangeListener.value(spo2, 0, pr, pi, resp, wave, -1);
        int status = ((data[0] >> 5) & 1)                       // probe unplugged
                | (((data[2] >> 4) & 1) << 1)                  // no finger
                | ((((data[0] >> 4) | (data[2] >> 5)) & 1) << 2) // no signal / pulse searching
                | (((data[0] >> 6) & 1) << 3);                 // beep
        mOnDataChangeListener.packet(lastChunkMs, "BCI", -1, status, spo2, -1, pr, -1, -1, data[0] & 0x0F, -1,
                wave, -1, -1, data[2] & 0x0F);
    }

    private String verText = "";
    private int verKind = 0;          // 0xFF software, 0xFE hardware
    private int verMore = 0;          // further 5-byte fragments expected

    /** BCI version reply: head 0xFF/0xFE then 4 printable ASCII bytes (zero padded), up to three packets. */
    private boolean bciVersion(int[] d) {
        if (d[0] != 0xFF && d[0] != 0xFE) return false;
        StringBuilder sb = new StringBuilder();
        boolean padding = false;
        for (int i = 1; i < 5; i++) {
            if (d[i] == 0) { padding = true; continue; }
            if (padding || d[i] < 0x20 || d[i] > 0x7E) return false;
            sb.append((char) d[i]);
        }
        String txt = sb.toString();
        boolean start = txt.matches("^[SH]?V\\d.*");
        boolean cont = verMore > 0 && d[0] == verKind && txt.matches("^[0-9.]+$");
        if (!start && !cont) return false;
        if (start) { verText = txt; verKind = d[0]; verMore = 2; } else { verText += txt; verMore--; }
        if (padding) verMore = 0;
        if (verKind == 0xFF) mOnDataChangeListener.softwareVersion(verText);
        else mOnDataChangeListener.hardwareVersion(verText);
        return true;
    }

    // ---------------------------------------------------------------- BERRY
    private void berryFrame(int[] data) {
        int packetFreq = data[18];
        if (packetFreq == 0 && (data[2] == 0x53 || data[2] == 0x48)) {
            // version reply: 'S'/'H' then an ASCII string, zero padded (PacketFreq is never 0 in data)
            StringBuilder sb = new StringBuilder();
            for (int k = 3; k < 19 && data[k] != 0; k++) sb.append((char) data[k]);
            if (data[2] == 0x53) mOnDataChangeListener.softwareVersion(sb.toString());
            else mOnDataChangeListener.hardwareVersion(sb.toString());
            return;
        }
        protoSeen("BERRY");
        int spo2 = data[4];
        int pr = data[6];
        int rr = (data[8] + (data[9] << 8)) * 5;          // 5 ms units
        double pi = data[10] / 10.0;
        int wave = data[12];
        mOnDataChangeListener.value(spo2, rr, pr, pi, 0, wave, packetFreq);
        long adc = (data[13]) | (data[14] << 8) | (data[15] << 16) | ((long) data[16] << 24);
        if (adc >= 0x80000000L) adc -= 0x100000000L;       // signed int32, little endian
        mOnDataChangeListener.packet(lastChunkMs, "BERRY", data[2], data[3], spo2, data[5], pr, data[7], rr, pi,
                data[11] / 10.0, wave, adc, data[17], packetFreq);
    }

    private double _calculatePi(int lower, int higher) {
        DecimalFormat df = new DecimalFormat("#.00");
        String value = df.format(((lower & 0x0F) + (higher & 0x0F) * 16) / 10.0);
        return Double.parseDouble(value);
    }

    public OnDataChangeListener getOnDataChangeListener() {
        return mOnDataChangeListener;
    }
}
