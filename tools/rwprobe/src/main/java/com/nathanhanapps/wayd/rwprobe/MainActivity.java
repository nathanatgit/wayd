// SPDX-License-Identifier: GPL-3.0-or-later
package com.nathanhanapps.wayd.rwprobe;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * WAYD test probe for rewrite rules, block lists and the DNS stage. It sends a fixed set of requests with fake marker
 * values to an echo server and prints what came back, so each WAYD rule has a visible PASS/INFO line. No real data is
 * sent: every identifier below is a constant. Results are shown on screen and written to result.txt in the app's
 * external files directory (readable with adb).
 */
public class MainActivity extends Activity {
    private static final String IDFA = "PROBE-IDFA-0000";
    private static final String DEVICE = "PROBE-DEVICE-0000";
    private TextView out;
    private EditText echo;
    private EditText blocked;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        echo = field("Echo base URL (returns the request as JSON)", extra("echo", "https://httpbin.org/anything"));
        blocked = field("Host expected to be on a WAYD block list", extra("blocked", "doubleclick.net"));
        Button run = new Button(this);
        run.setText("Run probe");
        run.setOnClickListener(v -> start());
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(out);

        root.addView(echo);
        root.addView(blocked);
        root.addView(run);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        if (getIntent().getBooleanExtra("run", false)) start();
    }

    private String extra(String key, String fallback) {
        String v = getIntent().getStringExtra(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private void start() {
        final String base = echo.getText().toString().trim().replaceAll("/+$", "");
        final String host = blocked.getText().toString().trim();
        log.setLength(0);
        out.setText("running…");
        new Thread(() -> {
            try {
                runAll(base, host);
            } catch (Throwable t) {
                line("probe crashed: " + t);
            }
            save();
        }, "probe").start();
    }

    private void runAll(String base, String blockedHost) {
        line("WAYD rewrite/list probe  package=" + getPackageName());
        line("echo=" + base + "  blocked=" + blockedHost);
        line("");

        line("[1] plain request (no rule should match)");
        Result plain = request("GET", base + "/plain?keep=1", null, false);
        report(plain);
        verdict("reaches the echo server unchanged", plain.ok() && plain.rewrite == null && plain.body.contains("keep"));

        line("[2] QUERY_REMOVE idfa + HEADER_REMOVE X-Device-Id + HEADER_SET User-Agent");
        Result strip = request("GET", base + "/strip?idfa=" + IDFA + "&keep=1", null, false);
        report(strip);
        info("query parameter idfa removed", strip.ok() && !strip.body.contains(IDFA));
        info("header X-Device-Id removed", strip.ok() && !strip.body.contains(DEVICE));
        info("User-Agent replaced", strip.ok() && !strip.body.contains("wayd-probe-agent"));
        verdict("other parameter kept", strip.ok() && strip.body.contains("keep"));

        line("[3] REJECT on POST /collect (local answer, body never sent)");
        Result reject = request("POST", base + "/collect", "{\"probe\":\"" + DEVICE + "\"}", false);
        report(reject);
        info("answered locally by WAYD", "reject".equals(reject.rewrite));

        line("[4] keep-alive: a plain request right after the rejected one");
        Result after = request("GET", base + "/plain?after=1", null, false);
        report(after);
        verdict("still reaches the server", after.ok() && after.rewrite == null && after.body.contains("after"));

        line("[5] REJECT with a chunked upload, then a plain request");
        Result chunked = request("POST", base + "/collect", repeat("chunk-" + DEVICE + "\n", 400), true);
        report(chunked);
        info("answered locally by WAYD", "reject".equals(chunked.rewrite));
        Result after2 = request("GET", base + "/plain?after=2", null, false);
        verdict("connection still usable afterwards", after2.ok() && after2.body.contains("after"));

        line("[6] REDIRECT on GET /old (redirects are not followed)");
        Result redirect = request("GET", base + "/old", null, false);
        report(redirect);
        info("local redirect by WAYD", "redirect".equals(redirect.rewrite) && redirect.location != null);

        line("[7] REGEX rule on /v2/ads");
        Result regex = request("GET", base + "/v2/ads?x=1", null, false);
        report(regex);
        info("answered locally by WAYD", "reject".equals(regex.rewrite));
        Result near = request("GET", base + "/v2/adsense", null, false);
        verdict("near-miss path /v2/adsense is not matched", near.ok() && near.rewrite == null);

        line("[8] block list: DNS stage and connection for " + blockedHost);
        long t0 = System.nanoTime();
        String addresses;
        try {
            StringBuilder sb = new StringBuilder();
            for (InetAddress a : InetAddress.getAllByName(blockedHost)) sb.append(a.getHostAddress()).append(' ');
            addresses = sb.toString().trim();
        } catch (Exception e) {
            addresses = "lookup failed: " + e.getClass().getSimpleName();
        }
        line("    dns " + ms(t0) + " ms -> " + addresses);
        info("DNS answered locally (0.0.0.0 / ::)", addresses.contains("0.0.0.0") || addresses.equals("::") || addresses.contains(":: ") || addresses.endsWith(" ::"));
        Result block = request("GET", "https://" + blockedHost + "/", null, false);
        report(block);
        info("connection did not succeed", !block.ok());

        line("[9] timing: three fresh requests to the echo host");
        long total = 0;
        for (int i = 0; i < 3; i++) {
            Result r = request("GET", base + "/plain?t=" + i, null, false);
            total += r.millis;
            line("    " + r.millis + " ms  status=" + r.status);
        }
        line("    average " + (total / 3) + " ms (compare with the WAYD list switched off)");
        line("");
        line("INFO lines say whether a WAYD rule acted; they are 'no' until that rule exists and the app is decrypted.");
        line("FAIL lines mean WAYD broke something it should have left alone.");
    }

    private static final class Result {
        int status = -1;
        long millis;
        String rewrite;
        String location;
        String body = "";
        String error;

        boolean ok() { return status >= 200 && status < 300; }
    }

    private Result request(String method, String url, String body, boolean chunked) {
        Result r = new Result();
        long t0 = System.nanoTime();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "wayd-probe-agent/1");
            c.setRequestProperty("X-Device-Id", DEVICE);
            c.setRequestProperty("X-Probe", "1");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                if (chunked) c.setChunkedStreamingMode(1024); else c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
            }
            r.status = c.getResponseCode();
            r.rewrite = c.getHeaderField("X-WAYD-Rewrite");
            r.location = c.getHeaderField("Location");
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) r.body = read(in);
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null && r.error != null) c.disconnect();
        }
        r.millis = ms(t0);
        return r;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < 256 * 1024) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private void report(Result r) {
        line("    " + r.millis + " ms  status=" + r.status
            + (r.rewrite != null ? "  X-WAYD-Rewrite=" + r.rewrite : "")
            + (r.location != null ? "  Location=" + r.location : "")
            + (r.error != null ? "  error=" + r.error : "")
            + "  body=" + r.body.length() + "B");
    }

    /** A check that must hold with or without WAYD rules. */
    private void verdict(String what, boolean pass) { line("    " + (pass ? "PASS" : "FAIL") + "  " + what); }

    /** A check that shows whether a WAYD rule acted. */
    private void info(String what, boolean yes) { line("    INFO  " + what + ": " + (yes ? "yes" : "no")); }

    private static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private void line(String s) {
        synchronized (log) { log.append(s).append('\n'); }
        final String text;
        synchronized (log) { text = log.toString(); }
        runOnUiThread(() -> out.setText(text));
    }

    private void save() {
        try {
            File f = new File(getExternalFilesDir(null), "result.txt");
            try (FileOutputStream o = new FileOutputStream(f)) {
                synchronized (log) { o.write(log.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            line("saved " + f.getAbsolutePath());
        } catch (Exception e) {
            line("could not save result: " + e);
        }
    }
}
