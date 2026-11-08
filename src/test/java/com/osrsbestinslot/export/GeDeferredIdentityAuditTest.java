package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Independent counterexample for deferred rows crossing the configured link identity. */
public class GeDeferredIdentityAuditTest {
    private static final String TOKEN_A="0123456789abcdef0123456789abcdef";
    private static final String TOKEN_B="fedcba9876543210fedcba9876543210";
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private AccountConnectPlugin p;
    private Client client;
    private String token=TOKEN_A;
    private int tick=1000;
    private String originalKey;

    private void inject(String name,Object value) throws Exception {
        Field f=AccountConnectPlugin.class.getDeclaredField(name);
        f.setAccessible(true); f.set(p,value);
    }
    @Before public void setup() throws Exception {
        p=new AccountConnectPlugin();
        inject("gson",new Gson());
        inject("config",new AccountConnectConfig() {
            @Override public String linkToken(){return token;}
        });
        client=mock(Client.class);
        when(client.getTickCount()).thenAnswer(i -> tick);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getAccountHash()).thenReturn(111L);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);
        inject("client",client);
        p.spooler.setSpoolDataRootForTest(tmp.newFolder("plugin-data").toPath());
        p.startUp();
        p.activeHash="111";
        p.activeProfileType="STANDARD";
        p.events.pendingEvents.clear();
        offer(0,0);
        originalKey=(String)row("ge_offer").get("offer_key");
        tick++;
        offer(10,2400);
        assertNull("partial is initially throttled",row("ge_progress"));
    }
    private void offer(int qty,long spent) {
        GrandExchangeOffer o=mock(GrandExchangeOffer.class);
        when(o.getState()).thenReturn(GrandExchangeOfferState.BUYING);
        when(o.getItemId()).thenReturn(560);
        when(o.getQuantitySold()).thenReturn(qty);
        when(o.getTotalQuantity()).thenReturn(100);
        when(o.getPrice()).thenReturn(300L);
        when(o.getSpent()).thenReturn(spent);
        GrandExchangeOfferChanged e=mock(GrandExchangeOfferChanged.class);
        when(e.getOffer()).thenReturn(o); when(e.getSlot()).thenReturn(0);
        p.onGrandExchangeOfferChanged(e);
    }
    private Map<String,Object> row(String type) {
        for(int i=p.events.pendingEvents.size()-1;i>=0;i--) {
            Map<String,Object> e=p.events.pendingEvents.get(i);
            if(type.equals(e.get("type")))return e;
        }
        return null;
    }
    @Test public void unchangedIdentityFlushesItsPendingFill() {
        tick=1101;
        p.ge.flushDue();
        Map<String,Object> row=row("ge_progress");
        assertNotNull(row);
        assertEquals(originalKey,row.get("offer_key"));
        assertEquals(10L,((Map<?,?>)row.get("ge_observation")).get("delta_qty"));
    }
    @Test public void tokenChangeMustNotRetagTheOldPendingFill() {
        token=TOKEN_B;
        ConfigChanged change=mock(ConfigChanged.class);
        when(change.getGroup()).thenReturn(AccountConnectPlugin.CONFIG_GROUP);
        when(change.getKey()).thenReturn("linkToken");
        when(change.getOldValue()).thenReturn(TOKEN_A);
        when(change.getNewValue()).thenReturn(TOKEN_B);
        p.onConfigChanged(change);
        tick=1101;
        p.ge.flushDue();
        Map<String,Object> row=row("ge_progress");
        if(row!=null) {
            System.out.println("old_offer_key_retained="+originalKey.equals(row.get("offer_key")));
            System.out.println("bound_to_new_token="+p.events.currentEventTokenFingerprint().equals(row.get(EventQueue.EVENT_TOKEN_FP)));
            System.out.println("delta_qty="+((Map<?,?>)row.get("ge_observation")).get("delta_qty"));
        }
        assertNull("A pending observation from the old token must not be retagged under the new link",row);
    }
}
