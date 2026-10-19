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
 * Usage: java ... WealthLedgerReplay [--out OUT.json] SNAP1.json SNAP2.json [SNAP3.json ...]
 * Snapshots are taken in order. Each holdings carries unread locations forward from the previous one (marked carried),
 * and one ledger is printed per consecutive pair. account_key is left out of the printed result.
 */
public final class WealthLedgerReplay
{
	public static void main(String[] args) throws Exception
	{
		List<String> files = new ArrayList<>(Arrays.asList(args));
		String outPath = null;
		if (files.size() > 1 && files.get(0).equals("--out"))
		{
			outPath = files.get(1);
			files = files.subList(2, files.size());
		}
		if (files.size() < 2)
		{
			System.err.println("usage: WealthLedgerReplay [--out OUT.json] SNAP1.json SNAP2.json [...]");
			System.exit(2);
		}
		List<Map<String, Object>> steps = new ArrayList<>();
		WealthLedger.Holdings prev = null;
		for (String f : files)
		{
			WealthLedger.Holdings h = WealthLedger.fromSnapshot(load(f)).carryForward(prev);
			Map<String, Object> step = new LinkedHashMap<>();
			step.put("file", Paths.get(f).getFileName().toString());
			Map<String, Object> view = h.toMap();
			view.remove("account_key");
			step.put("holdings", view);
			if (prev != null)
			{
				Map<String, Object> ledger = WealthReconciliation.reconcile(prev, h, null, null, null, null);
				ledger.remove("account_key");
				step.put("ledger_from_previous", ledger);
			}
			steps.add(step);
			prev = h;
		}
		String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(steps);
		if (outPath != null)
		{
			Files.write(Paths.get(outPath), json.getBytes(StandardCharsets.UTF_8));
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
