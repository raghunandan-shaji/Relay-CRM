package com.raghu.crm.config;

import com.raghu.crm.sales.LeadService;
import com.raghu.crm.engagement.EngagementService;
import com.raghu.crm.support.TicketService;
import com.raghu.crm.notifications.OutboxService;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SalesConfig {

	@Bean
	@ConditionalOnProperty(name = "crm.sales.initialize", havingValue = "true", matchIfMissing = true)
	ApplicationRunner initializeSalesIndexes(LeadService leads, EngagementService engagement, TicketService tickets,
			OutboxService outbox, MongoShardRegistry shards) {
		return args -> {
			leads.initializeIndexes(shards);
			engagement.initializeIndexes(shards);
			tickets.initializeIndexes();
			outbox.initializeIndexes();
		};
	}
}
