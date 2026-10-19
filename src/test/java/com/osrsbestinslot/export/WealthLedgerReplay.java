package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Offline replay for actual-client QA (test classpath only, not in the plugin JAR). Reads two snapshot bodies as
 * the plugin built them (either the bare snapshot map or the upload body {"snapshot": {...}}) and prints the
 * holdings and ledger. Prints counts and classes only, never the rsn.
 *
 * Usage: java ... WealthLedgerReplay FROM.json TO.json [OUT.json]
 */
public final class WealthLedgerReplay
{
	public static void main(String[] args) throws Exception
	{
		if (args.length < 2)
		{
			System.err.println("usage: WealthLedgerReplay FROM.json TO.json [OUT.json]");
			System.exit(2);
		}
		Map<String, Object> a = load(args[0]), b = load(args[1]);
		WealthLedger.Holdings ha = WealthLedger.fromSnapshot(a), hb = WealthLedger.fromSnapshot(b);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("holdings_from", ha.toMap());
		out.put("holdings_to", hb.toMap());
		out.put("ledger", WealthReconciliation.reconcile(ha, hb, null, null, null, null));
		Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
		String json = gson.toJson(out);
		if (args.length > 2)
		{
			Files.write(Paths.get(args[2]), json.getBytes(StandardCharsets.UTF_8));
		}
		System.out.println(json);
	}

	static Map<String, Object> load(String path) throws Exception
	{
		try (Reader r = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8))
		{
			Map<String, Object> m = new Gson().fromJson(r, new TypeToken<Map<String, Object>>()
			{
			}.getType());
			Object inner = m.get("snapshot");
			@SuppressWarnings("unchecked") Map<String, Object> snap = inner instanceof Map ? (Map<String, Object>) inner : m;
			return snap;
		}
	}

	private WealthLedgerReplay()
	{
	}
}
