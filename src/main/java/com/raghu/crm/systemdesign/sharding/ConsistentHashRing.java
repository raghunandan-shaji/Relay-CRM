package com.raghu.crm.systemdesign.sharding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

public final class ConsistentHashRing {

	private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	});

	private final NavigableMap<Long, String> ring = new TreeMap<>(Long::compareUnsigned);
	private final Set<String> nodes;

	public ConsistentHashRing(Collection<String> nodes, int virtualNodes) {
		if (nodes == null || nodes.isEmpty()) {
			throw new IllegalArgumentException("At least one node is required");
		}
		if (virtualNodes < 1) {
			throw new IllegalArgumentException("virtualNodes must be greater than zero");
		}

		this.nodes = Set.copyOf(nodes);
		if (this.nodes.size() != nodes.size()) {
			throw new IllegalArgumentException("Node IDs must be unique");
		}

		for (String node : this.nodes) {
			if (node == null || node.isBlank()) {
				throw new IllegalArgumentException("Node IDs must not be blank");
			}
			for (int replica = 0; replica < virtualNodes; replica++) {
				long point = hash(node + '#' + replica);
				if (ring.putIfAbsent(point, node) != null) {
					throw new IllegalStateException("Hash collision while building the ring");
				}
			}
		}
	}

	public String nodeFor(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("Routing key must not be blank");
		}

		var entry = ring.ceilingEntry(hash(key));
		return (entry != null ? entry : ring.firstEntry()).getValue();
	}

	public Set<String> nodes() {
		return new HashSet<>(nodes);
	}

	public int pointCount() {
		return ring.size();
	}

	private static long hash(String value) {
		MessageDigest digest = SHA_256.get();
		digest.reset();
		return ByteBuffer.wrap(digest.digest(value.getBytes(StandardCharsets.UTF_8))).getLong();
	}
}
