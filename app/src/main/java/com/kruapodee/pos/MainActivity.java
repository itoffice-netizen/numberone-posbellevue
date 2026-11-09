package com.kruapodee.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.webkit.SslErrorHandler;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import android.os.Bundle;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

/** หน้าต่างหลัก = ระบบ POS (WebView) ; จอที่ 2 = หน้าจอลูกค้า (Presentation) แยกจากจอแคชเชียร์ */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11;
    private WebView web;
    private CustomerScreen cs;
    private String last = "{\"k\":\"idle\"}";
    private SharedPreferences sp;
    private DisplayManager dm;
    private ValueCallback<Uri[]> fileCb;
    private int tapN;
    private long tapT;

    private final DisplayManager.DisplayListener dl = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { runOnUiThread(new Runnable() { public void run() { syncScreen(); } }); }
        @Override public void onDisplayRemoved(int id) { runOnUiThread(new Runnable() { public void run() { syncScreen(); } }); }
        @Override public void onDisplayChanged(int id) { }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("pos", MODE_PRIVATE);
        dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        web = new WebView(this);
        setContentView(web);
        setup(web);
        web.addJavascriptInterface(new Bridge(), "PosApp");
        initTts();
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("intent:")) {
                    try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "เปิดแอปปลายทางไม่ได้ (ติดตั้ง RawBT หรือยัง?)", Toast.LENGTH_LONG).show(); }
                    return true;
                }
                String base = baseUrl();
                if (url.startsWith("http") && base.length() > 0 && !Uri.parse(url).getHost().equals(Uri.parse(base).getHost())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { }
                    return true;
                }
                return false;
            }
            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                if (failingUrl != null && (failingUrl.equals(v.getUrl()) || failingUrl.startsWith(baseUrl()))) errorDialog("รหัส " + code + ": " + desc);
            }
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                h.cancel();
                if (e.getUrl() != null && e.getUrl().startsWith(baseUrl()))
                    errorDialog("ใบรับรองความปลอดภัย (SSL) ไม่ผ่าน (รหัส " + e.getPrimaryError() + ")\nมักเกิดจากวัน-เวลาของเครื่องไม่ตรง หรือ Android/WebView เก่าเกินไป");
            }
        });
        web.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN && e.getX() < 140 && e.getY() < 140) {
                    long t = System.currentTimeMillis();
                    tapN = (t - tapT < 3000) ? tapN + 1 : 1; tapT = t;
                    if (tapN >= 7) { tapN = 0; menu(); }
                }
                return false;
            }
        });
        dm.registerDisplayListener(dl, null);
        String u = baseUrl();
        if (u.length() == 0) askUrl(); else web.loadUrl(u);
    }

    static void setup(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
    }

    private String baseUrl() {
        String u = sp.getString("url", getString(R.string.default_url));
        int i = u.indexOf('?'); if (i >= 0) u = u.substring(0, i);
        i = u.indexOf('#'); if (i >= 0) u = u.substring(0, i);
        u = u.trim();
        if (u.length() > 0 && !u.startsWith("http")) u = "https://" + u;
        /* ที่อยู่ภายนอกต้องเป็น https เสมอ (Android บล็อก http) ยกเว้นเครือข่ายภายใน/localhost */
        if (u.startsWith("http://") && !u.matches("^http://(localhost|127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*")) u = "https://" + u.substring(7);
        if (u.length() > 0 && !u.endsWith("/")) u += "/";
        return u;
    }

    private void askUrl() {
        final EditText et = new EditText(this);
        String cur = sp.getString("url", getString(R.string.default_url));
        et.setText(cur.length() > 0 ? cur : "https://");
        new AlertDialog.Builder(this).setTitle("ที่อยู่ระบบ POS ของร้าน")
            .setMessage("เช่น https://ชื่อโดเมน/ชื่อร้าน/\n(เมนูตั้งค่า/ตรวจสอบ: แตะมุมซ้ายบน 7 ครั้งติดกัน)")
            .setView(et).setCancelable(false)
            .setPositiveButton("บันทึก", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String u = et.getText().toString().trim();
                    sp.edit().putString("url", u).apply();
                    web.loadUrl(baseUrl());
                    if (cs != null) { cs.dismiss(); cs = null; }
                    syncScreen();
                }
            }).show();
    }

    private String info() {
        String ua = web.getSettings().getUserAgentString(), cr = "?";
        int i = ua.indexOf("Chrome/"); if (i >= 0) { int j = ua.indexOf(' ', i); cr = ua.substring(i + 7, j > 0 ? j : ua.length()); }
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · WebView " + cr + "\nรุ่น: " + Build.MANUFACTURER + " " + Build.MODEL + "\nเวลาเครื่อง: " + new Date();
    }

    private void errorDialog(String detail) {
        new AlertDialog.Builder(this).setTitle("เชื่อมต่อระบบไม่ได้")
            .setMessage(detail + "\n\nที่อยู่: " + baseUrl() + "\n" + info())
            .setPositiveButton("ลองใหม่", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { web.loadUrl(baseUrl()); }
            })
            .setNeutralButton("ทดสอบเชื่อมต่อ", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { netTest(); }
            })
            .setNegativeButton("เปลี่ยนที่อยู่", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { askUrl(); }
            }).show();
    }

    private void menu() {
        new AlertDialog.Builder(this).setTitle("ตั้งค่า / ตรวจสอบ")
            .setItems(new String[]{"เปลี่ยนที่อยู่ระบบ", "ทดสอบการเชื่อมต่อ", "โหลดหน้าใหม่", "ล้างแคชของแอป"}, new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    if (w == 0) askUrl(); else if (w == 1) netTest(); else if (w == 2) web.loadUrl(baseUrl());
                    else { web.clearCache(true); web.loadUrl(baseUrl()); }
                }
            }).show();
    }

    /** ทดสอบเรียกเซิร์ฟเวอร์จากตัวเครื่องโดยตรง (ไม่ผ่าน WebView) เพื่อแยกว่าปัญหาอยู่ที่เครือข่าย/ใบรับรอง/วันเวลา หรือที่ WebView */
    private void netTest() {
        final String base = baseUrl();
        if (base.length() == 0) { askUrl(); return; }
        Toast.makeText(this, "กำลังทดสอบ…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                String r;
                try {
                    Uri u = Uri.parse(base);
                    String shop = u.getPathSegments().isEmpty() ? "" : u.getPathSegments().get(0);
                    HttpURLConnection c = (HttpURLConnection) new URL(u.getScheme() + "://" + u.getAuthority() + "/api/getBrand").openConnection();
                    c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-shop", shop);
                    OutputStream o = c.getOutputStream(); o.write("{\"a\":[]}".getBytes("UTF-8")); o.close();
                    int code = c.getResponseCode();
                    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                    byte[] buf = new byte[160]; int n = in == null ? 0 : in.read(buf); if (n < 0) n = 0;
                    r = "ติดต่อเซิร์ฟเวอร์ได้: HTTP " + code + "\n" + new String(buf, 0, n, "UTF-8");
                } catch (Exception e) {
                    r = "ติดต่อเซิร์ฟเวอร์ไม่ได้\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String msg = r + "\n\n" + info();
                runOnUiThread(new Runnable() { public void run() {
                    new AlertDialog.Builder(MainActivity.this).setTitle("ผลทดสอบการเชื่อมต่อ").setMessage(msg).setPositiveButton("ตกลง", null).show();
                } });
            }
        }).start();
    }

    /** หาจอที่ 2 แล้วเปิดหน้าจอลูกค้าไปที่จอนั้น */
    private void syncScreen() {
        if (isFinishing() || baseUrl().length() == 0) return;
        Display target = null;
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (ds.length > 0) target = ds[0];
        else for (Display d : dm.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) { target = d; break; }
        if (target == null) { if (cs != null) { cs.dismiss(); cs = null; } return; }
        if (cs != null && cs.getDisplay().getDisplayId() == target.getDisplayId() && cs.isShowing()) return;
        if (cs != null) { cs.dismiss(); cs = null; }
        try { cs = new CustomerScreen(this, target, baseUrl() + "d?app=1"); cs.show(); }
        catch (Exception e) { cs = null; }
    }

    @Override protected void onResume() { super.onResume(); syncScreen(); }
    @Override
    protected void onDestroy() {
        try { if (tts != null) tts.shutdown(); } catch (Exception e) { }
        try { dm.unregisterDisplayListener(dl); } catch (Exception e) { }
        if (cs != null) { cs.dismiss(); cs = null; }
        super.onDestroy();
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); }
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        } else super.onActivityResult(req, res, data);
    }

    /** ให้หน้าเว็บ POS เรียกผ่าน window.PosApp */
    private TextToSpeech tts; private boolean ttsOk = false; private int ttsState = 0; // 0=กำลังเตรียม 1=พร้อมภาษาไทย 2=ไม่มีภาษาไทย 3=ไม่มีเอนจิน
    private void initTts() {
        try {
            tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                public void onInit(int st) {
                    if (st != TextToSpeech.SUCCESS) { ttsState = 3; return; }
                    int r = tts.setLanguage(new Locale("th", "TH"));
                    ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                    ttsState = ttsOk ? 1 : 2;
                }
            });
        } catch (Exception e) { ttsState = 3; }
    }

    private class Bridge {
        /** อ่านออกเสียงด้วยเอนจิน TTS ของเครื่อง (WebView บน Android มักไม่มีเสียงให้ speechSynthesis) */
        @JavascriptInterface public boolean ttsSpeak(String text, float rate, float pitch, float vol) {
            if (tts == null || ttsState != 1) return false;
            try {
                tts.setSpeechRate(rate > 0 ? rate : 1f); tts.setPitch(pitch > 0 ? pitch : 1f);
                android.os.Bundle p = new android.os.Bundle(); p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol < 0 ? 1f : Math.min(1f, vol));
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "pos");
                return true;
            } catch (Exception e) { return false; }
        }
        /** 0=กำลังเตรียม 1=พร้อม (ภาษาไทย) 2=เครื่องไม่มีเสียงภาษาไทย 3=ไม่มีเอนจิน TTS */
        @JavascriptInterface public int ttsStatus() { return ttsState; }
        /** เมนูตั้งค่าแอป (เปลี่ยนลิงก์ร้าน / ทดสอบเชื่อมต่อ) เรียกจากปุ่มบนหน้าล็อกอิน */
        @JavascriptInterface public void appMenu() { runOnUiThread(new Runnable() { public void run() { menu(); } }); }
        @JavascriptInterface public void ttsSettings() {
            runOnUiThread(new Runnable() { public void run() {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e2) { } }
            } });
        }
        /** ติดตั้งข้อมูลเสียง (เปิดหน้าดาวน์โหลดเสียงของเอนจิน) */
        @JavascriptInterface public void ttsInstall() {
            runOnUiThread(new Runnable() { public void run() {
                try { startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { try { startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e2) { } }
            } });
        }
        @JavascriptInterface public void ttsRecheck() { runOnUiThread(new Runnable() { public void run() { if (tts != null) { try { tts.shutdown(); } catch (Exception e) { } } initTts(); } }); }
        @JavascriptInterface public void cdSend(final String json) {
            last = json;
            runOnUiThread(new Runnable() { public void run() { if (cs != null) cs.send(last); } });
        }
        @JavascriptInterface public boolean hasSecond() { return cs != null; }
    }

    /** จอลูกค้า: WebView เต็มจอที่ 2 ไม่รับการสัมผัส */
    private class CustomerScreen extends Presentation {
        private final String url;
        private WebView w;
        CustomerScreen(Context c, Display d, String url) { super(c, d); this.url = url; }
        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w = new WebView(getContext());
            setup(w);
            w.setFocusable(false);
            w.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, MotionEvent e) { return true; } });
            w.setWebViewClient(new WebViewClient() { @Override public void onPageFinished(WebView v, String u) { send(last); } });
            setContentView(w);
            w.loadUrl(url);
        }
        void send(String json) {
            if (w != null) w.evaluateJavascript("window.__cd&&window.__cd(JSON.parse(" + JSONObject.quote(json) + "))", null);
        }
    }
}
