package com.andrea.visualvideoscout;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText urlInput, queryInput, apiKeyInput;
    private TextView statusText;
    private final List<Candidate> candidates = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        urlInput = findViewById(R.id.urlInput);
        queryInput = findViewById(R.id.queryInput);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        statusText = findViewById(R.id.statusText);
        Button open = findViewById(R.id.openButton);
        Button scan = findViewById(R.id.scanButton);
        Button search = findViewById(R.id.searchButton);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadsImagesAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " VisualVideoScout/0.1");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new ScannerBridge(), "AndroidScanner");
        CookieManager.getInstance().setAcceptCookie(true);

        open.setOnClickListener(v -> openUrl());
        scan.setOnClickListener(v -> runScan());
        search.setOnClickListener(v -> runSearch());
    }

    private void openUrl() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        urlInput.setText(url);
        status("Caricamento: " + url);
        webView.loadUrl(url);
    }

    private void runScan() {
        status("Scansione DOM + hover in corso…");
        try (InputStream in = getAssets().open("scanner.js")) {
            byte[] bytes = new byte[in.available()];
            in.read(bytes);
            String js = new String(bytes, StandardCharsets.UTF_8);
            webView.evaluateJavascript(js, null);
        } catch (Exception e) { status("Errore scanner: " + e.getMessage()); }
    }

    private void runSearch() {
        final String q = queryInput.getText().toString().trim();
        if (q.isEmpty()) { status("Inserisci cosa vuoi trovare."); return; }
        if (candidates.isEmpty()) { status("Prima esegui Scansiona."); return; }
        final String key = apiKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            status("Modalità locale: filtro colore visuale. Per semantica completa inserisci una API key.");
            pool.submit(() -> localColorSearch(q));
        } else {
            status("Verifica semantica AI su thumbnail/preview…");
            pool.submit(() -> aiSearch(q, key));
        }
    }

    public class ScannerBridge {
        @JavascriptInterface public void onScanResults(String raw) {
            try {
                JSONObject root = new JSONObject(raw);
                JSONArray items = root.getJSONArray("items");
                synchronized (candidates) {
                    candidates.clear();
                    for (int i=0;i<items.length();i++) {
                        JSONObject o = items.getJSONObject(i);
                        Candidate c = new Candidate();
                        c.href=o.optString("href"); c.imageUrl=o.optString("imageUrl"); c.previewUrl=o.optString("previewUrl");
                        if (!c.imageUrl.isEmpty() || !c.previewUrl.isEmpty()) candidates.add(c);
                    }
                }
                status("Trovati " + candidates.size() + " candidati visuali. Ora scrivi la ricerca.");
            } catch (Exception e) { status("Risposta scanner non valida: " + e.getMessage()); }
        }
    }

    private void localColorSearch(String q) {
        String color = colorFromQuery(q);
        if (color == null) { status("Fallback locale MVP riconosce i colori. Prova 'rosso', oppure usa la modalità AI."); return; }
        List<Candidate> work;
        synchronized(candidates) { work = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), 80))); }
        int done=0;
        for (Candidate c : work) {
            if (c.imageUrl.isEmpty()) continue;
            try { c.score = colorScore(downloadBitmap(c.imageUrl), color); } catch (Exception ignored) { c.score = 0; }
            done++;
            if (done % 10 == 0) status("Analizzate " + done + "/" + work.size() + " immagini…");
        }
        work.sort(Comparator.comparingDouble((Candidate x)->x.score).reversed());
        showResults(work, "Locale · colore " + color);
    }

    private void aiSearch(String q, String key) {
        List<Candidate> work;
        synchronized(candidates) { work = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), 24))); }
        try {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type","input_text").put("text",
                "Trova quali immagini corrispondono alla richiesta: '" + q + "'. Valuta SOLO il contenuto visuale, non titolo/tag. " +
                "Rispondi esclusivamente JSON: {\"matches\":[{\"index\":0,\"score\":0.0,\"reason\":\"...\"}]}. Score 0..1."));
            int idx=0;
            Map<Integer,Candidate> map = new HashMap<>();
            for (Candidate c : work) {
                String image = null;
                if (!c.previewUrl.isEmpty()) image = previewFrameDataUrl(c.previewUrl);
                if (image == null || image.isEmpty()) image = c.imageUrl;
                if (image == null || image.isEmpty()) continue;
                content.put(new JSONObject().put("type","input_text").put("text","INDEX="+idx));
                content.put(new JSONObject().put("type","input_image").put("image_url", image).put("detail","low"));
                map.put(idx++, c);
                if (idx >= 12) break;
            }
            JSONObject req = new JSONObject()
                .put("model","gpt-5.6-luna")
                .put("input", new JSONArray().put(new JSONObject().put("role","user").put("content",content)))
                .put("text", new JSONObject().put("format", new JSONObject().put("type","json_object")));
            JSONObject resp = postJson("https://api.openai.com/v1/responses", req, key);
            String out = extractOutputText(resp);
            JSONObject parsed = new JSONObject(out);
            JSONArray matches = parsed.optJSONArray("matches");
            List<Candidate> ranked = new ArrayList<>();
            if (matches != null) for (int i=0;i<matches.length();i++) {
                JSONObject m = matches.getJSONObject(i);
                Candidate c = map.get(m.optInt("index",-1));
                if (c != null) { c.score=m.optDouble("score",0); c.reason=m.optString("reason",""); ranked.add(c); }
            }
            ranked.sort(Comparator.comparingDouble((Candidate x)->x.score).reversed());
            showResults(ranked, "AI semantica");
        } catch (Exception e) { status("Errore AI: " + e.getMessage()); }
    }

    private JSONObject postJson(String endpoint, JSONObject payload, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(endpoint).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Authorization","Bearer "+key);
        byte[] b=payload.toString().getBytes(StandardCharsets.UTF_8); c.getOutputStream().write(b);
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8)); StringBuilder sb=new StringBuilder(); String line;
        while((line=r.readLine())!=null) sb.append(line);
        if (c.getResponseCode() >= 400) throw new Exception("HTTP "+c.getResponseCode()+": "+sb);
        return new JSONObject(sb.toString());
    }

    private String extractOutputText(JSONObject r) throws Exception {
        JSONArray output=r.getJSONArray("output");
        for(int i=0;i<output.length();i++) {
            JSONArray content=output.getJSONObject(i).optJSONArray("content"); if(content==null) continue;
            for(int j=0;j<content.length();j++) if("output_text".equals(content.getJSONObject(j).optString("type"))) return content.getJSONObject(j).optString("text");
        }
        throw new Exception("Nessun output_text nella risposta");
    }

    private String previewFrameDataUrl(String url) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(url, new HashMap<>());
            Bitmap b = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG,75,out);
            return "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
        } catch(Exception e) { return null; } finally { try { mmr.release(); } catch(Exception ignored){} }
    }

    private Bitmap downloadBitmap(String u) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", webView.getSettings().getUserAgentString());
        String cookie=CookieManager.getInstance().getCookie(u); if(cookie!=null) c.setRequestProperty("Cookie",cookie);
        try(InputStream in=c.getInputStream()) { return BitmapFactory.decodeStream(in); }
    }

    private String colorFromQuery(String q) {
        q=q.toLowerCase(Locale.ITALIAN);
        if(q.contains("ross")||q.contains("red")) return "red";
        if(q.contains("blu")||q.contains("blue")) return "blue";
        if(q.contains("verd")||q.contains("green")) return "green";
        if(q.contains("giall")||q.contains("yellow")) return "yellow";
        if(q.contains("ner")||q.contains("black")) return "black";
        if(q.contains("bianc")||q.contains("white")) return "white";
        return null;
    }

    private double colorScore(Bitmap b, String target) {
        if (b==null) return 0; int w=b.getWidth(),h=b.getHeight(); int step=Math.max(1,Math.min(w,h)/80); long hit=0,total=0;
        for(int y=0;y<h;y+=step) for(int x=0;x<w;x+=step) { int p=b.getPixel(x,y); int r=(p>>16)&255,g=(p>>8)&255,bl=p&255; boolean ok=false;
            switch(target) { case "red": ok=r>120&&r>g*1.35&&r>bl*1.35; break; case "blue": ok=bl>110&&bl>r*1.25&&bl>g*1.15; break; case "green": ok=g>100&&g>r*1.2&&g>bl*1.1; break; case "yellow": ok=r>140&&g>120&&bl<110; break; case "black": ok=r<65&&g<65&&bl<65; break; case "white": ok=r>205&&g>205&&bl>205; break; }
            if(ok) hit++; total++; }
        return total==0?0:(double)hit/total;
    }

    private void showResults(List<Candidate> list, String mode) {
        StringBuilder sb=new StringBuilder(mode).append(" · Top risultati:\n"); int n=Math.min(8,list.size());
        for(int i=0;i<n;i++) { Candidate c=list.get(i); sb.append(i+1).append(") ").append(Math.round(c.score*100)).append("% ").append(c.href); if(!c.reason.isEmpty()) sb.append(" — ").append(c.reason); sb.append("\n"); }
        status(sb.toString());
        if(n>0) main.post(() -> webView.loadUrl(list.get(0).href));
    }

    private void status(String text) { main.post(() -> statusText.setText(text)); }

    @Override protected void onDestroy() { super.onDestroy(); pool.shutdownNow(); }

    static class Candidate { String href="",imageUrl="",previewUrl="",reason=""; double score=0; }
}
