package com.raghu.crm.config;

import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdGeneratorConfig {

	@Bean
	SnowflakeIdGenerator snowflakeIdGenerator(@Value("${crm.id.worker-id}") long workerId) {
		return new SnowflakeIdGenerator(workerId);
	}
}
