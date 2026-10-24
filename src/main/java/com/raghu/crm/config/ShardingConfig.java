package com.raghu.crm.config;

import com.raghu.crm.systemdesign.sharding.ConsistentHashRing;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import com.raghu.crm.systemdesign.sharding.ShardingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ShardingProperties.class)
public class ShardingConfig {

	@Bean
	ConsistentHashRing consistentHashRing(ShardingProperties properties) {
		return new ConsistentHashRing(
			properties.nodes().stream().map(ShardingProperties.Node::id).toList(),
			properties.virtualNodes());
	}

	@Bean(destroyMethod = "close")
	MongoShardRegistry mongoShardRegistry(ShardingProperties properties) {
		return new MongoShardRegistry(properties);
	}

	@Bean
	ShardRouter shardRouter(ConsistentHashRing ring, MongoShardRegistry registry) {
		return new ShardRouter(ring, registry);
	}
}
