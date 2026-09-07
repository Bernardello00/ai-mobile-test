package com.andrea.visualvideoscout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClipTokenizer {
    static final int CONTEXT_LENGTH = 77;

    private final Map<String, Integer> vocab = new HashMap<>();
    private final Map<String, Integer> mergeRanks = new HashMap<>();
    private final Map<Integer, Character> byteEncoder = new HashMap<>();
    private final Map<String, String> cache = new HashMap<>();
    private final Pattern tokenPattern = Pattern.compile(
        "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private final int bosId;
    private final int eosId;
    private final int padId;

    ClipTokenizer(String tokenizerJson) throws Exception {
        JSONObject root = new JSONObject(tokenizerJson);
        JSONObject model = root.getJSONObject("model");
        JSONObject jsonVocab = model.getJSONObject("vocab");
        Iterator<String> keys = jsonVocab.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            vocab.put(key, jsonVocab.getInt(key));
        }

        JSONArray merges = model.getJSONArray("merges");
        for (int i = 0; i < merges.length(); i++) {
            Object value = merges.get(i);
            String pair;
            if (value instanceof JSONArray) {
                JSONArray a = (JSONArray) value;
                pair = a.getString(0) + " " + a.getString(1);
            } else {
                pair = String.valueOf(value);
            }
            mergeRanks.put(pair, i);
        }

        buildByteEncoder();
        bosId = vocab.containsKey("<|startoftext|>") ? vocab.get("<|startoftext|>") : 49406;
        eosId = vocab.containsKey("<|endoftext|>") ? vocab.get("<|endoftext|>") : 49407;
        padId = vocab.containsKey("!") ? vocab.get("!") : 0;
    }

    Encoding encode(String text) {
        long[] ids = new long[CONTEXT_LENGTH];
        long[] mask = new long[CONTEXT_LENGTH];
        for (int i = 0; i < ids.length; i++) ids[i] = padId;

        List<Integer> tokens = new ArrayList<>();
        tokens.add(bosId);
        String clean = whitespaceClean(text).toLowerCase(Locale.ROOT);
        Matcher matcher = tokenPattern.matcher(clean);
        while (matcher.find()) {
            String token = matcher.group();
            byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
            StringBuilder encoded = new StringBuilder();
            for (byte b : bytes) encoded.append(byteEncoder.get(b & 0xff));
            String[] bpePieces = bpe(encoded.toString()).split(" ");
            for (String piece : bpePieces) {
                Integer id = vocab.get(piece);
                if (id != null) tokens.add(id);
            }
        }
        tokens.add(eosId);

        if (tokens.size() > CONTEXT_LENGTH) {
            tokens = new ArrayList<>(tokens.subList(0, CONTEXT_LENGTH));
            tokens.set(CONTEXT_LENGTH - 1, eosId);
        }
        for (int i = 0; i < tokens.size(); i++) {
            ids[i] = tokens.get(i);
            mask[i] = 1L;
        }
        return new Encoding(ids, mask);
    }

    private String bpe(String token) {
        synchronized (cache) {
            String hit = cache.get(token);
            if (hit != null) return hit;
        }
        if (token.isEmpty()) return token;

        List<String> word = new ArrayList<>();
        int[] cps = token.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            String s = new String(Character.toChars(cps[i]));
            if (i == cps.length - 1) s += "</w>";
            word.add(s);
        }

        while (word.size() > 1) {
            Set<String> pairs = new LinkedHashSet<>();
            for (int i = 0; i < word.size() - 1; i++) pairs.add(word.get(i) + " " + word.get(i + 1));

            String best = null;
            int bestRank = Integer.MAX_VALUE;
            for (String pair : pairs) {
                Integer rank = mergeRanks.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    best = pair;
                }
            }
            if (best == null) break;

            int split = best.indexOf(' ');
            String first = best.substring(0, split);
            String second = best.substring(split + 1);
            List<String> merged = new ArrayList<>();
            int i = 0;
            while (i < word.size()) {
                if (i < word.size() - 1 && word.get(i).equals(first) && word.get(i + 1).equals(second)) {
                    merged.add(first + second);
                    i += 2;
                } else {
                    merged.add(word.get(i));
                    i++;
                }
            }
            word = merged;
        }

        String result = String.join(" ", word);
        synchronized (cache) {
            if (cache.size() < 4096) cache.put(token, result);
        }
        return result;
    }

    private void buildByteEncoder() {
        List<Integer> bs = new ArrayList<>();
        for (int i = 33; i <= 126; i++) bs.add(i);
        for (int i = 161; i <= 172; i++) bs.add(i);
        for (int i = 174; i <= 255; i++) bs.add(i);
        List<Integer> cs = new ArrayList<>(bs);
        Set<Integer> initial = new HashSet<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!initial.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }
        for (int i = 0; i < bs.size(); i++) byteEncoder.put(bs.get(i), (char) cs.get(i).intValue());
    }

    private String whitespaceClean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    static final class Encoding {
        final long[] inputIds;
        final long[] attentionMask;
        Encoding(long[] inputIds, long[] attentionMask) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
        }
    }
}
