package com.raghu.crm.config;

import com.raghu.crm.customer.CustomerSearchService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomerSearchConfig {

	@Bean
	@ConditionalOnProperty(name = "crm.search.initialize", havingValue = "true", matchIfMissing = true)
	ApplicationRunner initializeCustomerSearch(CustomerSearchService search) {
		return args -> search.initialize();
	}
}
