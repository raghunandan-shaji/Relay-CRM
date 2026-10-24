package com.raghu.crm.systemdesign.sharding;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashRingTests {

	private static final int KEY_COUNT = 100_000;
	private static final int VIRTUAL_NODES = 256;

	@Test
	void validatesConfigurationAndRoutingKeys() {
		assertThrows(IllegalArgumentException.class, () -> new ConsistentHashRing(List.of(), VIRTUAL_NODES));
		assertThrows(IllegalArgumentException.class, () -> new ConsistentHashRing(List.of("a", "a"), VIRTUAL_NODES));
		assertThrows(IllegalArgumentException.class, () -> new ConsistentHashRing(List.of("a"), 0));

		ConsistentHashRing ring = new ConsistentHashRing(List.of("a"), VIRTUAL_NODES);
		assertThrows(IllegalArgumentException.class, () -> ring.nodeFor(""));
		assertEquals(VIRTUAL_NODES, ring.pointCount());
	}

	@Test
	void distributesCustomersAcrossThreeShards() {
		ConsistentHashRing ring = new ConsistentHashRing(List.of("shard-0", "shard-1", "shard-2"), VIRTUAL_NODES);
		Map<String, Integer> counts = distribution(ring);

		for (int count : counts.values()) {
			double share = count / (double) KEY_COUNT;
			assertTrue(share >= 0.25 && share <= 0.42, "Unbalanced distribution: " + counts);
		}

		System.out.println("Three-shard distribution: " + counts);
	}

	@Test
	void addingAShardMovesFewerKeysThanModuloRouting() {
		ConsistentHashRing before = new ConsistentHashRing(List.of("shard-0", "shard-1", "shard-2"), VIRTUAL_NODES);
		ConsistentHashRing after = new ConsistentHashRing(List.of("shard-0", "shard-1", "shard-2", "shard-3"), VIRTUAL_NODES);
		int consistentHashMoves = 0;
		int moduloMoves = 0;

		for (int i = 0; i < KEY_COUNT; i++) {
			String key = "customer-" + i;
			String previousShard = before.nodeFor(key);
			String nextShard = after.nodeFor(key);
			if (!previousShard.equals(nextShard)) {
				consistentHashMoves++;
				assertEquals("shard-3", nextShard);
			}
			if (Math.floorMod(key.hashCode(), 3) != Math.floorMod(key.hashCode(), 4)) {
				moduloMoves++;
			}
		}

		double consistentPercent = percentage(consistentHashMoves);
		double moduloPercent = percentage(moduloMoves);
		assertTrue(consistentPercent < moduloPercent);
		assertTrue(consistentPercent >= 15.0 && consistentPercent <= 35.0);

		System.out.printf("Add-shard movement: consistent %.2f%%, modulo %.2f%%%n",
			consistentPercent, moduloPercent);
	}

	@Test
	void removingAShardDoesNotMoveKeysOwnedByOtherShards() {
		ConsistentHashRing before = new ConsistentHashRing(List.of("shard-0", "shard-1", "shard-2"), VIRTUAL_NODES);
		ConsistentHashRing after = new ConsistentHashRing(List.of("shard-0", "shard-1"), VIRTUAL_NODES);
		int moved = 0;

		for (int i = 0; i < KEY_COUNT; i++) {
			String key = "customer-" + i;
			String previousShard = before.nodeFor(key);
			String nextShard = after.nodeFor(key);
			if (!previousShard.equals(nextShard)) {
				moved++;
				assertEquals("shard-2", previousShard);
			}
		}

		double movedPercent = percentage(moved);
		assertTrue(movedPercent >= 20.0 && movedPercent <= 45.0);
		System.out.printf("Remove-shard movement: %.2f%%%n", movedPercent);
	}

	private static Map<String, Integer> distribution(ConsistentHashRing ring) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		ring.nodes().stream().sorted().forEach(node -> counts.put(node, 0));
		for (int i = 0; i < KEY_COUNT; i++) {
			counts.compute(ring.nodeFor("customer-" + i), (node, count) -> count + 1);
		}
		return counts;
	}

	private static double percentage(int count) {
		return count * 100.0 / KEY_COUNT;
	}
}
