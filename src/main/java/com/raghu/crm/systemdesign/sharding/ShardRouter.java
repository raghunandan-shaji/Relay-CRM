package com.raghu.crm.systemdesign.sharding;

import org.springframework.data.mongodb.core.MongoTemplate;

public final class ShardRouter {

	private final ConsistentHashRing ring;
	private final MongoShardRegistry registry;

	public ShardRouter(ConsistentHashRing ring, MongoShardRegistry registry) {
		this.ring = ring;
		this.registry = registry;
	}

	public String shardIdFor(String routingKey) {
		return ring.nodeFor(routingKey);
	}

	public MongoTemplate templateFor(String routingKey) {
		return registry.template(shardIdFor(routingKey));
	}
}
