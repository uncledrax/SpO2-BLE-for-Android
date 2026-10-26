package com.berry_med.bci.blutooth;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/*
 * description: ParseRunnable
 * author: zl
 * date: 2024/10/23 10:29
 */
public class ParseRunnable implements Runnable {
    protected LinkedBlockingQueue<Integer> oxiData = new LinkedBlockingQueue<>(256);
    protected int[] parseBuf = new int[20];
    protected OnDataChangeListener mOnDataChangeListener;

    protected String currentModel = "";
    private List<Integer> buffArray;

    boolean isStop = false;

    public ParseRunnable(OnDataChangeListener onDataChangeListener) {
        isStop = true;
        this.mOnDataChangeListener = onDataChangeListener;
    }

    public void setStop(boolean stop) {
        isStop = stop;
    }

    public void init() {
        buffArray = new ArrayList<>();
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
    }

    private volatile long lastChunkMs = 0;

    /**
     * Add the data from Bluetooth Device to The Queue.
     *
     * @param data data from Bluetooth Device
     */
    public void add(byte[] data) {
        try {
            if (data != null) {
                lastChunkMs = System.currentTimeMillis();
                // Version replies are recognised inside the packet parsers (bciParse / berryParse). The old
                // chunk-level "contains SV/HV" test threw away whole notifications whenever those two ASCII
                // bytes occurred by chance in binary data - with the BERRY ADC bytes that is every ~30 s.
                if (data.length >= 2) {
                    for (byte d : data) oxiData.put(toUnsignedInt(d));
                }
            }
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
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

    /**
     * Parsing the protocol as the manual.
     */
    @Override
    public void run() {
        int PACKAGE_LEN = 20;
        int pending = -1;                       // a head byte that arrived early (resync)
        while (isStop) {
            int data = pending >= 0 ? pending : getData();
            pending = -1;
            if (Model.MODEL.equals("BERRY")) {
                parseBuf[0] = data;
                for (int i = 1; i < PACKAGE_LEN; i++) {
                    parseBuf[i] = getData();
                }
                berryParse(parseBuf);
            } else {
                if ((data & 0x80) == 0) continue;   // not a packet head
                parseBuf[0] = data;
                int i = 1;
                while (i < 5) {
                    data = getData();
                    if ((data & 0x80) != 0) {       // a head inside the packet: packet was truncated
                        pending = data;
                        break;
                    }
                    parseBuf[i++] = data;
                }
                if (i == 5) bciParse(parseBuf);
            }
        }
    }

    // BCI
    private void bciParse(int[] data) {
        if (bciVersion(data)) return;
        verMore = 0;
        int beep = data[0];
        int wave = data[1];
        int spo2 = data[4];
        int pr = data[3] | ((data[2] & 0x40) << 1);
        int resp = 0;
        double pi = 0.0;
        if (Model.MODEL.equals("BCI-RESP")) {
            pi = _calculatePi(beep, data[2]);
            resp = data[6];
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

    public void berryParse(int[] array) {
        // Convert int[] to List<Integer> and add to buffArray
        for (int value : array) {
            buffArray.add(value);
        }

        int i = 0; // Current Index
        int validIndex = 0; // Valid Index
        int maxIndex = buffArray.size() - 20; // Data Space

        while (i <= maxIndex) {
            // Failed to match the headers
            if (buffArray.get(i) != 0xFF || buffArray.get(i + 1) != 0xAA) {
                i += 1;
                validIndex = i;
                continue;
            }

            // The header is successfully matched
            int total = 0;
            int checkSum = buffArray.get(i + 19);
            for (int index = 0; index <= 18; index++) {
                total += buffArray.get(i + index);
            }

            // If the verification fails, discard the two data
            if (checkSum != (total % 256)) {
                i += 2;
                validIndex = i;
                continue;
            }

            // Extracting a sublist for data
            List<Integer> data = buffArray.subList(i, i + 19);

            if (data.size() >= 19) {
                int spo2 = data.get(4);
                int pr = data.get(6);
                int rr = (data.get(8) + (data.get(9) << 8)) * 5; // 200Hz
                double pi = data.get(10) / 10.0;
                int wave = data.get(12);
                int packetFreq = data.get(18);

                if (packetFreq == 0 && (data.get(2) == 0x53 || data.get(2) == 0x48)) {
                    // version reply: 'S'/'H' then an ASCII string, zero padded (PacketFreq is never 0 in data)
                    StringBuilder sb = new StringBuilder();
                    for (int k = 3; k < 19 && data.get(k) != 0; k++) sb.append((char) (int) data.get(k));
                    if (data.get(2) == 0x53) mOnDataChangeListener.softwareVersion(sb.toString());
                    else mOnDataChangeListener.hardwareVersion(sb.toString());
                } else {
                    mOnDataChangeListener.value(spo2, rr, pr, pi, 0, wave, packetFreq);
                    long adc = (data.get(13)) | (data.get(14) << 8) | (data.get(15) << 16) | ((long) data.get(16) << 24);
                    if (adc >= 0x80000000L) adc -= 0x100000000L;   // signed int32, little endian
                    mOnDataChangeListener.packet(lastChunkMs, "BERRY", data.get(2), data.get(3), spo2, data.get(5), pr,
                            data.get(7), rr, pi, data.get(11) / 10.0, wave, adc, data.get(17), packetFreq);
                }
            }
            i += 20; // Move back one group
            validIndex = i;
        }

        // Update buffArray to retain only valid data
        buffArray = new ArrayList<>(buffArray.subList(validIndex, buffArray.size()));
    }

    private int toUnsignedInt(byte x) {
        return ((int) x) & 0xFF;
    }

    private int getData() {
        try {
            return oxiData.take();
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
        return 0;
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
