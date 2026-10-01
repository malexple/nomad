package org.nomad.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.nomad.core.BinReader;
import org.nomad.core.BinWriter;

class GroupNameTest {
    private record Pair(Identity admin, Identity member, GroupManager ga, GroupManager gb, String gid) {}

    private static Pair group(String name) {
        Identity a = Identity.generate();
        Identity b = Identity.generate();
        GroupManager ga = new GroupManager(a);
        GroupManager gb = new GroupManager(b);
        GroupManager.CreatedGroup created = ga.createGroup(name, List.of(new GroupManager.GroupMember(b.sigPub())));
        List<GroupManager.Outbound> follow = gb.handleControl(a.uid(), created.outbound().get(0).plaintext());
        ga.handleControl(b.uid(), follow.get(0).plaintext());
        return new Pair(a, b, ga, gb, created.groupId());
    }

    @Test
    void theNameTravelsToTheMembers() {
        Pair p = group("Семья");
        assertEquals("Семья", p.ga().listGroups().get(0).name());
        assertEquals("Семья", p.gb().listGroups().get(0).name());
        assertEquals(p.admin().uid(), p.gb().listGroups().get(0).adminUid());
    }

    @Test
    void theNameSurvivesAPersistenceRoundTrip() {
        Pair p = group("Дача");
        BinWriter w = new BinWriter();
        p.gb().writeTo(w);
        GroupManager restored = GroupManager.readFrom(p.member(), new BinReader(w.toByteArray()));
        assertEquals("Дача", restored.listGroups().get(0).name());
    }

    @Test
    void aGroupWithoutANameHasAnEmptyName() {
        assertEquals("", group("").gb().listGroups().get(0).name());
        assertEquals("", group("   ").gb().listGroups().get(0).name());
    }

    @Test
    void aLongNameIsCutToTheLimitWithoutBreakingTheGroup() {
        Pair p = group("я".repeat(200));
        String name = p.gb().listGroups().get(0).name();
        assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= GroupManager.MAX_NAME_BYTES);
        assertFalse(name.isEmpty());
    }

    @Test
    void renamingByTheAdminReachesTheMember() {
        Pair p = group("Старое");
        List<GroupManager.Outbound> state = p.ga().updateGroup(
                p.gid(), "Новое", List.of(new GroupManager.GroupMember(p.member().sigPub())));
        p.gb().handleControl(p.admin().uid(), state.get(0).plaintext());
        assertEquals("Новое", p.gb().listGroups().get(0).name());
        assertEquals("Новое", p.ga().listGroups().get(0).name());
    }
}
