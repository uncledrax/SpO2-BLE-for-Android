package com.berry_med.bci.utils;

import android.os.Build;
import android.os.Environment;

import com.berry_med.bci.application.MyApplication;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/*
 * @Description File
 * @Author zl
 * @Date 2025/3/30 11:26
 */
public class MyFiles {
    private String filePath = "";
    private BufferedWriter writer;
    private BufferedWriter csv;
    private long pktIndex = 0;
    public static final String CSV_HEADER = "unix_time_s,pkt_index,proto,dev_pkt_index,pleth,adc,spo2,spo2_real,pr,pr_real,"
            + "rr_interval_ms,pi,pi_real,signal_strength,bargraph,no_signal,probe_unplugged,no_finger,pulse_searching,beep,"
            + "battery,packet_freq";

    // sdCard path
    private String getInnerSDCardPath() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File file = MyApplication.getContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (file == null) return "";
            return file.getAbsolutePath() + File.separator + "Spo2BleForAndroid";
        }
        return Environment.getExternalStorageDirectory().getPath();
    }

    // Create Txt
    public void createTxt() {
        File file = new File(getInnerSDCardPath());
        if (!file.exists()) file.mkdirs();
        filePath = file.getPath() + File.separator + "TestRecord.txt";
        try {
            FileOutputStream outputStream = new FileOutputStream(filePath, false);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            writer = new BufferedWriter(outputStreamWriter);
            writer.write("Time,RR,Pr,SpO₂\r\n");
            writer.flush();
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }

    // Write txt
    public void writeTxt(long time, int rr, int pr, int spo2) {
        try {
            if (writer != null) {
                writer.write(time + "," + rr + "," + pr + "," + spo2 + "\r\n");
                writer.flush();
            }
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }

    // Per-packet CSV (every BCI / BERRY packet, host-timestamped) - readable by tools/finger_ppg_compare.py
    public void createCsv() {
        File dir = new File(getInnerSDCardPath());
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        filePath = dir.getPath() + File.separator + "berrymed_" + stamp + ".csv";
        try {
            csv = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath, false), StandardCharsets.UTF_8), 1 << 16);
            csv.write(CSV_HEADER + "\n");
            csv.flush();
            pktIndex = 0;
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }

    private static String v(int x) { return x < 0 ? "" : String.valueOf(x); }
    private static String v(long x) { return x == -1 ? "" : String.valueOf(x); }

    public synchronized void writePacket(long hostMs, String proto, int devIdx, int status, int spo2, int spo2Real, int pr,
                                         int prReal, int rrMs, double pi, double piReal, int wave, long adc, int battery, int freq) {
        if (csv == null) return;
        boolean bci = "BCI".equals(proto);
        String spo2s = (spo2 == 127 || spo2 < 0) ? "" : String.valueOf(spo2);
        String prs = (pr == 255 || pr < 0) ? "" : String.valueOf(pr);
        try {
            csv.write(String.format(Locale.US, "%d.%03d,%d,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%s,%d,%s,%s\n",
                    hostMs / 1000, hostMs % 1000, pktIndex++, proto, v(devIdx), wave, v(adc), spo2s,
                    (spo2Real == 127 || spo2Real < 0) ? "" : String.valueOf(spo2Real), prs,
                    (prReal == 255 || prReal < 0) ? "" : String.valueOf(prReal), rrMs <= 0 ? "" : String.valueOf(rrMs),
                    (bci || pi <= 0) ? "" : String.valueOf(pi), piReal <= 0 ? "" : String.valueOf(piReal),
                    bci ? String.valueOf((int) pi) : "", bci ? v(freq) : "",
                    (status >> 2) & 1, status & 1, (status >> 1) & 1, bci ? String.valueOf((status >> 2) & 1) : "",
                    (status >> 3) & 1, v(battery), bci ? "100" : v(freq)));
            if (pktIndex % 200 == 0) csv.flush();
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }

    public synchronized void closeCsv() {
        try {
            if (csv != null) { csv.flush(); csv.close(); }
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
        csv = null;
    }

    public void close() {
        closeCsv();
        try {
            if (writer != null) writer.close();
        } catch (Exception e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
        }
    }

    public String getFilePath() {
        return filePath;
    }
}
