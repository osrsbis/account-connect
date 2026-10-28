package com.osrsbestinslot.export;

import java.lang.reflect.Field;

/**
 * The server's staff-capture answer for these contracts. Since 2026-10-28 the staff tools run in the one
 * Hub plugin, and the server turns them on only for an approved staff token and the account it names
 * (X-Staff-Capture: on, X-Staff-Account). Each contract below describes a staff client, so its fixture
 * receives that answer when it links a token. The assertions themselves are unchanged.
 */
final class StaffGrant
{
    static final String ACCOUNT = "12345";

    private StaffGrant() {}

    /**
     * The grant for the plugin's current token and logged-in account, as applyServerPolicy stores it. The
     * logged-in account is the injected client's, when the fixture has one (a mock answers 0), so a row the
     * plugin stamps with it is never taken for another account's.
     */
    static void give(Object target) throws Exception
    {
        if (!(target instanceof AccountConnectPlugin)) return;
        AccountConnectPlugin plugin = (AccountConnectPlugin) target;
        Field hash = AccountConnectPlugin.class.getDeclaredField("activeHash");
        hash.setAccessible(true);
        Field clientField = AccountConnectPlugin.class.getDeclaredField("client");
        clientField.setAccessible(true);
        Object client = clientField.get(plugin);
        Object current = hash.get(plugin);
        if (current == null || isFixtureAccount(current))
        {
            hash.set(plugin, client instanceof net.runelite.api.Client
                ? Long.toString(((net.runelite.api.Client) client).getAccountHash()) : ACCOUNT);
        }
        // The fixture's own server stands in for osrsbestinslot.com, the only endpoint that may grant this.
        Field config = AccountConnectPlugin.class.getDeclaredField("config");
        config.setAccessible(true);
        Object c = config.get(plugin);
        String base = c instanceof AccountConnectConfig ? ((AccountConnectConfig) c).apiBaseUrl() : null;
        plugin.staffApiBase = base == null || base.trim().isEmpty() ? AccountConnectPlugin.OFFICIAL_API_BASE : base.trim();
        plugin.setStaffCaptureForTest(plugin.currentLinkToken(), (String) hash.get(plugin));
    }

    private static boolean isFixtureAccount(Object hash)
    {
        return ACCOUNT.equals(hash) || "0".equals(hash);
    }

    /** Called by each contract's field injector: linking a token, or attaching the client, refreshes the grant. */
    static void afterInject(Object target, String field) throws Exception
    {
        if ("config".equals(field) || "client".equals(field)) give(target);
    }
}
