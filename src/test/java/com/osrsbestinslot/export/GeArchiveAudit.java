package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Private, read-only archive audit. Test classpath only; never included in the public plugin JAR. */
public final class GeArchiveAudit
{
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel", "ge_collect"));
    private static final Gson JSON = new Gson();

    private GeArchiveAudit() {}

    /** Only aggregate quality counts leave this function; account, token, offer and item IDs do not. */
    static Map<String, Object> audit(List<Map<String, Object>> rows, long ack)
    {
        if (ack <= 0 || rows.size() > 80_000) throw new IllegalArgumentException("audit bound");
        Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int geRows = 0, eligible = 0;
        for (Map<String, Object> row : rows)
        {
            if (!TYPES.contains(row.get("type"))) continue;
            geRows++;
            Long id = integer(row.get("id"));
            if (id == null || id < 1 || id > ack) throw new IllegalArgumentException("row outside durable ACK");
            Map<?, ?> fields = fields(row.get("fields"));
            if (fields == null) { count(reasons, "malformed_fields"); continue; }
            String account = nonempty(row.get("account_hash"));
            String token = nonempty(row.get("link_token"));
            String profile = nonempty(fields.get("profile_type"));
            boolean versioned = fields.get("ge_observation") instanceof Map
                && nonempty(fields.get("offer_key")) != null;
            if (!versioned) count(reasons, "missing_versioned_observation");
            if (profile == null) count(reasons, "missing_mode");
            if (account == null || token == null) count(reasons, "missing_owner_binding");
            if (!versioned || profile == null || account == null || token == null) continue;
            if (fields.containsKey("account_hash") && !account.equals(fields.get("account_hash")))
            { count(reasons, "conflicting_account"); continue; }
            Map<String, Object> event = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : fields.entrySet())
                if (e.getKey() instanceof String) event.put((String) e.getKey(), e.getValue());
            // Archived routing identity wins over untrusted payload fields.
            event.put("account_hash", account);
            event.put("type", row.get("type"));
            event.put("ts", row.get("ts"));
            if (nonempty(event.get("event_id")) == null && nonempty(row.get("event_id")) != null)
                event.put("event_id", row.get("event_id"));
            if (nonempty(event.get("event_id")) == null)
            { count(reasons, "missing_event_id"); continue; }
            // Never join a re-bound token or a different explicit profile. Missing account_type is not STANDARD.
            List<String> binding = Arrays.asList(token, account, profile, nonempty(fields.get("account_type")));
            groups.computeIfAbsent(binding, k -> new ArrayList<>()).add(event);
            eligible++;
        }
        int offers = 0, quarantined = 0, qualified = 0, fillIntervals = 0;
        Map<String, Long> diagnostics = new TreeMap<>();
        for (List<Map<String, Object>> events : groups.values())
        {
            Map<String, Object> result = GeOfferLifecycle.reduce(events, null, "private_archive_partition");
            for (Object value : (List<?>) result.get("offers"))
            {
                Map<?, ?> offer = (Map<?, ?>) value;
                offers++;
                if (Boolean.TRUE.equals(offer.get("quarantined"))) quarantined++;
                else
                {
                    qualified++;
                    fillIntervals += ((List<?>) offer.get("fills")).size();
                }
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) result.get("diagnostics")).entrySet())
                diagnostics.merge((String) entry.getKey(), ((Number) entry.getValue()).longValue(), Long::sum);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "private-ge-archive-quality-v1");
        report.put("rows_scanned", rows.size());
        report.put("ge_rows", geRows);
        report.put("eligible_versioned_rows", eligible);
        report.put("excluded_rows", geRows - eligible);
        report.put("exclusion_reasons", reasons);
        report.put("offers", offers);
        report.put("quarantined_offers", quarantined);
        report.put("qualified_offers", qualified);
        report.put("observed_fill_intervals", fillIntervals);
        report.put("reducer_diagnostics", diagnostics);
        report.put("durable_ack", ack);
        report.put("scope", "bounded archive observations; not independent-player coverage, actual execution times or GP/tax proof");
        return report;
    }

    private static void count(Map<String, Integer> map, String reason) { map.merge(reason, 1, Integer::sum); }
    private static String nonempty(Object value)
    { return value instanceof String && !((String) value).trim().isEmpty() ? (String) value : null; }
    private static Long integer(Object value)
    {
        if (!(value instanceof Number)) return null;
        double n = ((Number) value).doubleValue();
        return Double.isFinite(n) && n == Math.rint(n) && Math.abs(n) <= 9_007_199_254_740_991L
            ? ((Number) value).longValue() : null;
    }
    private static Map<?, ?> fields(Object value)
    {
        if (value instanceof String)
        {
            if (((String) value).length() > 200_000) return null;
            try { value = JSON.fromJson((String) value, Object.class); }
            catch (RuntimeException ignored) { return null; }
        }
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    /** Usage: GeArchiveAudit ARCHIVE_ROOT ACK ARCHIVE.jsonl.gz [up to four exact existing archive files]. */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        if (args.length < 3 || args.length > 6) throw new IllegalArgumentException("root, ACK and 1..4 archives required");
        Path root = Paths.get(args[0]).toRealPath();
        long ack = Long.parseLong(args[1]);
        Set<Path> seen = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        long readCharacters = 0;
        for (int i = 2; i < args.length; i++)
        {
            Path path = Paths.get(args[i]);
            Path real = path.toRealPath();
            if (Files.isSymbolicLink(path) || !real.startsWith(root) || !seen.add(real)
                || Files.size(real) > 50_000_000) throw new IllegalArgumentException("archive outside audit bound");
            int count = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(real)), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    readCharacters += line.length();
                    if (++count > 20_000 || line.length() > 200_000 || readCharacters > 50_000_000)
                        throw new IllegalArgumentException("archive row bound");
                    Map<String, Object> row = JSON.fromJson(line, Map.class);
                    if (row == null) throw new IllegalArgumentException("invalid archive row");
                    rows.add(row);
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(real));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b & 255));
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("file", real.getFileName().toString());
            source.put("sha256", hex.toString());
            source.put("rows", count);
            sources.add(source);
        }
        Map<String, Object> report = audit(rows, ack);
        report.put("sources", sources);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
}
