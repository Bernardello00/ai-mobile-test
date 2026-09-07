package com.andrea.visualvideoscout;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int MAX_PENDING_FRAMES = 3;

    private WebView webView;
    private EditText urlInput, queryInput, maxPagesInput, maxCardsInput;
    private TextView statusText, modelText;
    private LinearLayout resultsContainer;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService modelInitPool = Executors.newSingleThreadExecutor();
    private final ExecutorService inferencePool = Executors.newSingleThreadExecutor();
    private final Map<String, Candidate> candidateMap = new LinkedHashMap<>();
    private final Set<String> visitedFingerprints = new HashSet<>();
    private final Set<String> visitedUrls = new HashSet<>();
    private final AtomicInteger pendingInference = new AtomicInteger();
    private final AtomicInteger indexedFrames = new AtomicInteger();

    private volatile MobileClipEngine clipEngine;
    private volatile boolean modelInitializing;
    private volatile boolean destroyed;
    private volatile boolean scanning;
    private volatile int scanSession;
    private boolean pageScanInProgress;
    private boolean waitingForNavigation;
    private boolean waitingForClickNavigation;
    private int pageCount;
    private int maxPages = 10;
    private int maxCardsPerPage = 80;
    private String scannerJs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        urlInput = findViewById(R.id.urlInput);
        queryInput = findViewById(R.id.queryInput);
        maxPagesInput = findViewById(R.id.maxPagesInput);
        maxCardsInput = findViewById(R.id.maxCardsInput);
        statusText = findViewById(R.id.statusText);
        modelText = findViewById(R.id.modelText);
        resultsContainer = findViewById(R.id.resultsContainer);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadsImagesAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " VisualVideoScout/0.2.2");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new ScannerBridge(), "AndroidScanner");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!scanning) return;
                if (waitingForNavigation) {
                    waitingForNavigation = false;
                    main.postDelayed(() -> scanCurrentPageIfPossible(), 700);
                } else if (waitingForClickNavigation) {
                    // A real document navigation occurred. If it was a same-content reload,
                    // the fingerprint fence below will stop it instead of looping forever.
                    waitingForClickNavigation = false;
                    status(progress("Navigazione pagina successiva confermata · riparto dall'inizio della nuova pagina…"));
                    main.postDelayed(() -> scanCurrentPageIfPossible(), 700);
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
        });

        findViewById(R.id.openButton).setOnClickListener(v -> openUrl());
        findViewById(R.id.scanButton).setOnClickListener(v -> startScan());
        findViewById(R.id.searchButton).setOnClickListener(v -> runSearch());
        try { scannerJs = readAssetText("scanner.js"); }
        catch (Exception e) { status("Errore scanner: " + e.getMessage()); }
        ensureModelReady(null);
    }

    private void ensureModelReady(Runnable afterReady) {
        if (clipEngine != null) { if (afterReady != null) main.post(afterReady); return; }
        if (modelInitializing) { if (afterReady != null) main.postDelayed(() -> ensureModelReady(afterReady), 400); return; }
        modelInitializing = true;
        modelStatus("MobileCLIP S0 FP32 · caricamento encoder vision on-device…");
        modelInitPool.submit(() -> {
            try {
                MobileClipEngine engine = new MobileClipEngine(getApplicationContext());
                if (destroyed) { engine.close(); return; }
                clipEngine = engine;
                modelStatus("MobileCLIP S0 FP32 · READY · memoria bounded");
                if (afterReady != null) main.post(afterReady);
            } catch (Throwable e) {
                modelStatus("MobileCLIP non disponibile: " + safeMessage(e));
                status("Errore modello locale: " + safeMessage(e));
            } finally { modelInitializing = false; }
        });
    }

    private String normalizedInputUrl() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) { status("Inserisci un URL."); return ""; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        urlInput.setText(url);
        return url;
    }

    private void openUrl() {
        scanning = false;
        waitingForNavigation = false;
        waitingForClickNavigation = false;
        String url = normalizedInputUrl();
        if (!url.isEmpty()) { status("Caricamento: " + url); webView.loadUrl(url); }
    }

    private void startScan() {
        String target = normalizedInputUrl();
        if (target.isEmpty()) return;
        maxPages = clampInt(maxPagesInput.getText().toString(), 10, 1, 100);
        maxCardsPerPage = clampInt(maxCardsInput.getText().toString(), 80, 10, 250);
        maxPagesInput.setText(String.valueOf(maxPages));
        maxCardsInput.setText(String.valueOf(maxCardsPerPage));
        status("Preparazione encoder vision MobileCLIP…");
        ensureModelReady(() -> prepareVisionThen(() -> beginScan(target)));
    }

    private void prepareVisionThen(Runnable afterReady) {
        modelInitPool.submit(() -> {
            try {
                MobileClipEngine e = clipEngine;
                if (e == null) throw new IllegalStateException("MobileCLIP non inizializzato");
                e.prepareVision();
                modelStatus("MobileCLIP S0 FP32 · VISION READY");
                main.post(afterReady);
            } catch (Throwable t) {
                status("Impossibile preparare l'encoder vision: " + safeMessage(t));
            }
        });
    }

    private void beginScan(String target) {
        scanSession++;
        scanning = true;
        pageScanInProgress = false;
        waitingForNavigation = false;
        waitingForClickNavigation = false;
        pageCount = 0;
        pendingInference.set(0);
        indexedFrames.set(0);
        synchronized (candidateMap) { candidateMap.clear(); }
        visitedFingerprints.clear();
        visitedUrls.clear();
        resultsContainer.removeAllViews();
        String current = webView.getUrl();
        if (current == null || !sameUrl(current, target)) {
            waitingForNavigation = true;
            status("Apro il catalogo e avvio il crawl multipagina…");
            webView.loadUrl(target);
        } else scanCurrentPageIfPossible();
    }

    private void scanCurrentPageIfPossible() {
        if (!scanning || pageScanInProgress || scannerJs == null) return;
        if (pageCount >= maxPages) { finishScan("Raggiunto limite di " + maxPages + " pagine."); return; }
        String current = webView.getUrl();
        if (current == null || current.startsWith("about:")) { finishScan("Pagina non disponibile."); return; }
        pageCount++;
        pageScanInProgress = true;
        status(progress("Pagina " + pageCount + "/" + maxPages + " · scroll, hover e preview…"));
        String js = "window.__VVS_SESSION=" + scanSession + ";window.__VVS_PAGE=" + pageCount +
            ";window.__VVS_MAX_CARDS=" + maxCardsPerPage + ";\n" + scannerJs;
        webView.evaluateJavascript(js, null);
    }

    public class ScannerBridge {
        @JavascriptInterface public int getPendingInference() { return pendingInference.get(); }

        @JavascriptInterface public void onVisualFrame(String raw) {
            try {
                JSONObject o = new JSONObject(raw);
                if (o.optInt("session", -1) != scanSession || !scanning) return;
                main.post(() -> captureAndIndex(o));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void onScanResults(String raw) {
            try {
                JSONObject root = new JSONObject(raw);
                if (root.optInt("session", -1) != scanSession || !scanning) return;
                JSONArray items = root.optJSONArray("items");
                if (items != null) synchronized (candidateMap) {
                    for (int i = 0; i < items.length(); i++) upsertCandidate(items.getJSONObject(i));
                }
                main.post(() -> handlePageResult(root));
            } catch (Exception e) { main.post(() -> finishScan("Risposta scanner non valida: " + e.getMessage())); }
        }

        @JavascriptInterface public void onNavigationChanged(String raw) {
            try {
                JSONObject o = new JSONObject(raw);
                if (o.optInt("session", -1) != scanSession || !scanning) return;
                main.post(() -> {
                    if (!waitingForClickNavigation) return;
                    waitingForClickNavigation = false;
                    String page = o.optString("pageNumber", "");
                    status(progress("Pagina successiva confermata" + (page.isEmpty() ? "" : " (pagina " + page + ")") + " · scansione…"));
                    main.postDelayed(() -> scanCurrentPageIfPossible(), 450);
                });
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void onNavigationFailed(String raw) {
            try {
                JSONObject o = new JSONObject(raw);
                if (o.optInt("session", -1) != scanSession || !scanning) return;
                main.post(() -> {
                    if (!waitingForClickNavigation) return;
                    waitingForClickNavigation = false;
                    finishScan("Il controllo Next è stato cliccato ma URL/pagina/contenuto non sono cambiati. Stop invece di riscannerizzare la stessa pagina.");
                });
            } catch (Exception ignored) { }
        }
    }

    private void captureAndIndex(JSONObject o) {
        if (!scanning || clipEngine == null || o.optInt("session", -1) != scanSession) return;
        if (pendingInference.get() >= MAX_PENDING_FRAMES) return;
        try {
            int vw = Math.max(1, o.optInt("viewportWidth", 1));
            int vh = Math.max(1, o.optInt("viewportHeight", 1));
            double x = o.optDouble("x", -1), y = o.optDouble("y", -1);
            double w = o.optDouble("width", 0), h = o.optDouble("height", 0);
            if (x < -10 || y < -10 || w < 80 || h < 60 || webView.getWidth() < 1 || webView.getHeight() < 1) return;

            Candidate c;
            synchronized (candidateMap) { c = upsertCandidate(o); }
            String tag = o.optInt("page", pageCount) + ":" + o.optString("stage", "frame");
            synchronized (c.frameTags) { if (!c.frameTags.add(tag)) return; }

            double sx = webView.getWidth() / (double) vw, sy = webView.getHeight() / (double) vh;
            int l = clamp((int)Math.floor(x*sx),0,webView.getWidth()-1);
            int t = clamp((int)Math.floor(y*sy),0,webView.getHeight()-1);
            int r = clamp((int)Math.ceil((x+w)*sx),l+1,webView.getWidth());
            int b = clamp((int)Math.ceil((y+h)*sy),t+1,webView.getHeight());
            if (r-l < 60 || b-t < 50) return;

            int[] loc = new int[2];
            webView.getLocationInWindow(loc);
            Rect src = new Rect(loc[0]+l, loc[1]+t, loc[0]+r, loc[1]+b);
            Bitmap card = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(getWindow(), src, card, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        Bitmap small = downscale(card, 256);
                        if (small != card) card.recycle();
                        enqueueEmbedding(c, small);
                    } else {
                        card.recycle();
                        Bitmap fallback = fallbackCrop(l,t,r,b);
                        if (fallback != null) enqueueEmbedding(c, fallback);
                    }
                }, main);
            } catch (Throwable e) {
                card.recycle();
                Bitmap fallback = fallbackCrop(l,t,r,b);
                if (fallback != null) enqueueEmbedding(c, fallback);
            }
        } catch (OutOfMemoryError oom) {
            finishScan("Memoria Android sotto pressione durante la cattura frame. Scansione fermata in sicurezza: riduci Card/pagina.");
        } catch (Throwable t) {
            status(progress("Frame saltato: " + safeMessage(t)));
        }
    }

    private Bitmap fallbackCrop(int l, int t, int r, int b) {
        try {
            Bitmap viewport = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
            webView.draw(new Canvas(viewport));
            Bitmap crop = Bitmap.createBitmap(viewport,l,t,r-l,b-t);
            viewport.recycle();
            Bitmap small = downscale(crop,256);
            if (small != crop) crop.recycle();
            return small;
        } catch (Throwable ignored) { return null; }
    }

    private void enqueueEmbedding(Candidate c, Bitmap bitmap) {
        if (pendingInference.get() >= MAX_PENDING_FRAMES) { bitmap.recycle(); return; }
        pendingInference.incrementAndGet();
        inferencePool.submit(() -> {
            try {
                MobileClipEngine engine = clipEngine;
                if (engine != null) {
                    float[] v = engine.embedImage(bitmap);
                    synchronized (c.embeddings) { c.embeddings.add(v); }
                    indexedFrames.incrementAndGet();
                }
            } catch (OutOfMemoryError oom) {
                main.post(() -> finishScan("Memoria insufficiente durante MobileCLIP. Ho fermato la scansione senza chiudere l'app; prova meno Card/pagina."));
            } catch (Throwable t) {
                status(progress("Inference frame saltata: " + safeMessage(t)));
            } finally {
                if (!bitmap.isRecycled()) bitmap.recycle();
                int left = pendingInference.decrementAndGet();
                if (!scanning && left == 0) status(progress("Indicizzazione MobileCLIP completata."));
            }
        });
    }

    private Candidate upsertCandidate(JSONObject o) {
        String href = o.optString("href", "");
        String source = o.optString("sourceUrl", "");
        String image = o.optString("imageUrl", "");
        String preview = o.optString("previewUrl", "");
        String key = !href.isEmpty() ? href : source + "|" + image + "|" + o.optString("id", "");
        Candidate c = candidateMap.get(key);
        if (c == null) {
            c = new Candidate(); c.key=key; c.href=href; c.sourceUrl=source; c.imageUrl=image; c.previewUrl=preview;
            candidateMap.put(key,c);
        } else {
            if (c.imageUrl.isEmpty()) c.imageUrl=image;
            if (c.previewUrl.isEmpty()) c.previewUrl=preview;
            if (c.sourceUrl.isEmpty()) c.sourceUrl=source;
        }
        return c;
    }

    private void handlePageResult(JSONObject root) {
        pageScanInProgress = false;
        String fp = root.optString("fingerprint", "");
        String current = root.optString("url", webView.getUrl()==null?"":webView.getUrl());
        String pageNo = root.optString("currentPage", "");
        if (!fp.isEmpty() && !visitedFingerprints.add(fp)) { finishScan("Contenuto già visitato: stop anti-loop."); return; }
        if (!current.isEmpty()) visitedUrls.add(canonicalUrl(current));
        if (pageCount >= maxPages) { finishScan("Raggiunto limite di " + maxPages + " pagine."); return; }

        String nextUrl = root.optString("nextUrl", "");
        boolean click = root.optBoolean("hasNextClick", false);
        String nextLabel = root.optString("nextLabel", "");
        String identity = root.optString("identity", "");
        if (!nextUrl.isEmpty() && !visitedUrls.contains(canonicalUrl(nextUrl))) {
            waitingForNavigation = true;
            status(progress("Pagina " + (pageNo.isEmpty()?String.valueOf(pageCount):pageNo) + " acquisita · apro " + (nextLabel.isEmpty()?"pagina successiva":nextLabel) + "…"));
            webView.loadUrl(nextUrl);
            return;
        }
        if (click) {
            waitingForClickNavigation = true;
            status(progress("Pagina acquisita · click su " + (nextLabel.isEmpty()?"Next/paginazione":nextLabel) + " e verifica cambio contenuto…"));
            String safeIdentity = identity.replace("\\", "\\\\").replace("'", "\\'");
            String js = "(function(){if(typeof window.__VVS_clickNextAndProbe!=='function')return false;return window.__VVS_clickNextAndProbe('" + safeIdentity + "');})()";
            webView.evaluateJavascript(js, result -> {
                if (!"true".equals(result)) {
                    waitingForClickNavigation=false;
                    finishScan("Controllo pagina successiva non più disponibile.");
                }
            });
            return;
        }
        finishScan("Fine paginazione rilevata.");
    }

    private void finishScan(String reason) {
        scanning=false; pageScanInProgress=false; waitingForNavigation=false; waitingForClickNavigation=false;
        status(progress(reason + " Puoi cercare quando la coda frame arriva a 0."));
    }

    private void runSearch() {
        String q = queryInput.getText().toString().trim();
        if (q.isEmpty()) { status("Scrivi cosa vuoi trovare, per esempio: uomo con maglietta rossa."); return; }
        if (scanning) { status(progress("La scansione è ancora attiva: attendi la fine prima della ricerca.")); return; }
        if (pendingInference.get() > 0) { status(progress("Sto finendo gli ultimi frame: attendi che la coda arrivi a 0.")); return; }
        if (clipEngine == null) { status("MobileCLIP si sta inizializzando."); ensureModelReady(this::runSearch); return; }
        List<Candidate> snapshot;
        synchronized (candidateMap) { snapshot = new ArrayList<>(candidateMap.values()); }
        if (snapshot.isEmpty()) { status("Prima avvia la scansione del sito."); return; }
        status("Carico l'encoder text on-device e cerco su " + snapshot.size() + " video/card…");
        modelStatus("MobileCLIP S0 FP32 · passaggio VISION → TEXT…");
        inferencePool.submit(() -> {
            try {
                float[] text = clipEngine.embedText(q);
                modelStatus("MobileCLIP S0 FP32 · TEXT READY");
                List<Candidate> ranked = new ArrayList<>();
                for (Candidate c : snapshot) {
                    double best=-1;
                    synchronized (c.embeddings) { for (float[] image : c.embeddings) best=Math.max(best,MobileClipEngine.cosine(text,image)); }
                    if (best > -0.5) { c.score=best; ranked.add(c); }
                }
                ranked.sort(Comparator.comparingDouble((Candidate x)->x.score).reversed());
                showResults(ranked,q);
            } catch (OutOfMemoryError oom) {
                status("Memoria insufficiente caricando l'encoder text. Chiudi altre app e riprova; la scansione indicizzata resta disponibile.");
            } catch (Throwable e) { status("Errore MobileCLIP: " + safeMessage(e)); }
        });
    }

    private void showResults(List<Candidate> ranked, String q) {
        main.post(() -> {
            resultsContainer.removeAllViews();
            int n=Math.min(10,ranked.size());
            if (n==0) { status("Nessun frame indicizzato: ripeti la scansione con più Card/pagina."); return; }
            for (int i=0;i<n;i++) {
                Candidate c=ranked.get(i);
                Button b=new Button(this);
                String target=!c.href.isEmpty()?c.href:c.sourceUrl;
                b.setText(String.format(Locale.ROOT,"%d · CLIP %.3f",i+1,c.score));
                b.setAllCaps(false);
                b.setOnClickListener(v -> { if (target!=null && !target.isEmpty()) webView.loadUrl(target); });
                resultsContainer.addView(b);
            }
            status("Query: “"+q+"” · "+n+" risultati. Tocca un risultato per aprirlo.");
        });
    }

    private Bitmap downscale(Bitmap source,int maxSide) {
        int max=Math.max(source.getWidth(),source.getHeight());
        if (max<=maxSide) return source;
        float k=maxSide/(float)max;
        return Bitmap.createScaledBitmap(source,Math.max(1,Math.round(source.getWidth()*k)),Math.max(1,Math.round(source.getHeight()*k)),true);
    }

    private String progress(String prefix) {
        int c; synchronized(candidateMap){c=candidateMap.size();}
        String encoder = clipEngine == null ? "none" : clipEngine.activeEncoder();
        return prefix+"\nPagine: "+pageCount+"/"+maxPages+" · candidati: "+c+" · frame CLIP: "+indexedFrames.get()+" · coda: "+pendingInference.get()+" · encoder: "+encoder;
    }

    private int clampInt(String value,int fallback,int min,int max) {
        try { return clamp(Integer.parseInt(value.trim()),min,max); } catch(Exception e){ return fallback; }
    }
    private static int clamp(int v,int min,int max){ return Math.max(min,Math.min(max,v)); }
    private boolean sameUrl(String a,String b){ return canonicalUrl(a).equals(canonicalUrl(b)); }
    private String canonicalUrl(String v){ if(v==null)return""; int h=v.indexOf('#'); return (h>=0?v.substring(0,h):v).replaceAll("/$",""); }

    private String readAssetText(String path) throws Exception {
        try(InputStream in=getAssets().open(path); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[65536]; int n; while((n=in.read(buf))>0)out.write(buf,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private String safeMessage(Throwable t){ String m=t==null?null:t.getMessage(); return (m==null||m.trim().isEmpty())?(t==null?"errore sconosciuto":t.getClass().getSimpleName()):m; }
    private void status(String t){ main.post(() -> statusText.setText(t)); }
    private void modelStatus(String t){ main.post(() -> modelText.setText(t)); }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && scanning) status(progress("Android segnala memoria bassa: il crawler applica backpressure."));
    }

    @Override protected void onDestroy(){
        destroyed=true; scanning=false; inferencePool.shutdownNow(); modelInitPool.shutdownNow();
        MobileClipEngine e=clipEngine; clipEngine=null; if(e!=null)try{e.close();}catch(Exception ignored){}
        if(webView!=null)webView.destroy(); super.onDestroy();
    }

    static final class Candidate {
        String key="",href="",sourceUrl="",imageUrl="",previewUrl=""; double score;
        final List<float[]> embeddings=Collections.synchronizedList(new ArrayList<>());
        final Set<String> frameTags=Collections.synchronizedSet(new HashSet<>());
    }
}
