package com.raghu.crm.config;

import com.raghu.crm.sales.LeadService;
import com.raghu.crm.engagement.EngagementService;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SalesConfig {

	@Bean
	@ConditionalOnProperty(name = "crm.sales.initialize", havingValue = "true", matchIfMissing = true)
	ApplicationRunner initializeSalesIndexes(LeadService leads, EngagementService engagement, MongoShardRegistry shards) {
		return args -> {
			leads.initializeIndexes(shards);
			engagement.initializeIndexes(shards);
		};
	}
}
