package com.osrsbestinslot.export;

import java.io.File;
import java.lang.reflect.Field;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Fast checks on the exact candidate JAR before the longer delivery-proof suite. There is one plugin for
 * every player (2026-10-28), so the candidate is the Hub JAR itself: the server's staff-capture
 * grant for the logged-in account must turn every staff tool on, and nothing else may.
 */
public class StaffCapabilitiesTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String ACCOUNT = "12345";
    private AccountConnectPlugin plugin;

    @Before public void linked() throws Exception
    {
        String expected = System.getProperty("staff.candidate.jar");
        assertNotNull("staff gate must identify the exact candidate JAR", expected);
        File loaded = new File(AccountConnectPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals("workspace classes masked the candidate JAR", new File(expected).getCanonicalFile(), loaded.getCanonicalFile());
        plugin = new AccountConnectPlugin();
        set("config", new AccountConnectConfig()
        {
            @Override public String linkToken() { return TOKEN; }
        });
        set("activeHash", ACCOUNT);
    }

    private void set(String name, Object value) throws Exception
    {
        Field f = AccountConnectPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(plugin, value);
    }

    /** One server answer, through the same policy path a real upload response takes. */
    private void answer(String staffCapture, String staffAccount)
    {
        Response.Builder b = new Response.Builder().request(new Request.Builder().url(AccountConnectPlugin.OFFICIAL_API_BASE + "/account-ingest").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("X-Store-Tools", "on").header("X-Drop-Proof", "on")
            .header("X-Screenshots", "allow").header("X-Clips", "allow");
        if (staffCapture != null) b.header("X-Staff-Capture", staffCapture);
        if (staffAccount != null) b.header("X-Staff-Account", staffAccount);
        plugin.applyServerPolicy(b.build(), TOKEN);
    }

    private void assertAllOff(String why)
    {
        assertFalse(why + ": trade screenshots", plugin.screenshotsEnabled());
        assertFalse(why + ": store clips", plugin.storeClipsEnabled());
        assertFalse(why + ": drop proof", plugin.dropProofEnabled());
        assertFalse(why + ": staff capture", plugin.staffCaptureEnabled());
    }

    @Test public void staffGrantTurnsOnTradeStoreAndDropProof()
    {
        answer("on", ACCOUNT);
        assertTrue("STAFF RELEASE BLOCKED: trade screenshots unavailable in candidate", plugin.screenshotsEnabled());
        assertTrue("STAFF RELEASE BLOCKED: store clips unavailable in candidate", plugin.storeClipsEnabled());
        assertTrue("STAFF RELEASE BLOCKED: drop proof unavailable in candidate", plugin.dropProofEnabled());
    }

    @Test public void everyOtherAnswerLeavesThePlayerWithoutStaffTools()
    {
        answer(null, null);
        assertAllOff("store tools, clips and drop proof without the staff grant");
        answer("on", null);
        assertAllOff("a grant naming no account");
        answer("on", "99999");
        assertAllOff("a grant for another account");
        answer("off", ACCOUNT);
        assertAllOff("an explicit off");
    }

    @Test public void theGrantEndsWhenTheServerStopsSendingIt()
    {
        answer("on", ACCOUNT);
        assertTrue(plugin.staffCaptureEnabled());
        answer(null, null);
        assertAllOff("a later answer without the grant");
    }
}
