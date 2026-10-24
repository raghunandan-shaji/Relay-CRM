package com.raghu.crm.systemdesign.sharding;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "crm.sharding")
public record ShardingProperties(int virtualNodes, List<Node> nodes) {

	public ShardingProperties {
		if (virtualNodes < 1) {
			throw new IllegalArgumentException("crm.sharding.virtual-nodes must be greater than zero");
		}
		if (nodes == null || nodes.isEmpty()) {
			throw new IllegalArgumentException("At least one CRM shard must be configured");
		}

		nodes = List.copyOf(nodes);
		Set<String> ids = new HashSet<>();
		for (Node node : nodes) {
			if (node.id() == null || node.id().isBlank()) {
				throw new IllegalArgumentException("Shard ID must not be blank");
			}
			if (node.uri() == null || node.uri().isBlank()) {
				throw new IllegalArgumentException("Shard URI must not be blank");
			}
			if (!ids.add(node.id())) {
				throw new IllegalArgumentException("Duplicate shard ID: " + node.id());
			}
		}
	}

	public record Node(String id, String uri) {
	}
}
