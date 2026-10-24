package com.raghu.crm.systemdesign.sharding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class MongoShardRegistryIntegrationTests {

	@Autowired
	private MongoShardRegistry registry;

	@Test
	void allConfiguredShardsRespondToPing() {
		Map<String, Boolean> statuses = registry.pingAll();

		assertEquals(3, statuses.size());
		assertEquals(Map.of("shard-0", true, "shard-1", true, "shard-2", true), statuses);
		System.out.println("MongoDB shard health: " + statuses);
	}
}
