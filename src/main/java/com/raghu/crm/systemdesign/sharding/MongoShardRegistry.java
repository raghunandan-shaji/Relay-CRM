package com.raghu.crm.systemdesign.sharding;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class MongoShardRegistry implements AutoCloseable {

	private final Map<String, MongoClient> clients;
	private final Map<String, MongoTemplate> templates;

	public MongoShardRegistry(ShardingProperties properties) {
		Map<String, MongoClient> clients = new LinkedHashMap<>();
		Map<String, MongoTemplate> templates = new LinkedHashMap<>();

		try {
			for (ShardingProperties.Node node : properties.nodes()) {
				ConnectionString connectionString = new ConnectionString(node.uri());
				String database = connectionString.getDatabase();
				if (database == null || database.isBlank()) {
					throw new IllegalArgumentException("Shard URI must include a database: " + node.id());
				}

				MongoClient client = MongoClients.create(connectionString);
				clients.put(node.id(), client);
				templates.put(node.id(), new MongoTemplate(client, database));
			}
		} catch (RuntimeException exception) {
			clients.values().forEach(MongoClient::close);
			throw exception;
		}

		this.clients = Map.copyOf(clients);
		this.templates = Map.copyOf(templates);
	}

	public MongoTemplate template(String shardId) {
		MongoTemplate template = templates.get(shardId);
		if (template == null) {
			throw new IllegalArgumentException("Unknown shard: " + shardId);
		}
		return template;
	}

	public Map<String, Boolean> pingAll() {
		Map<String, Boolean> statuses = new LinkedHashMap<>();
		for (Map.Entry<String, MongoTemplate> entry : templates.entrySet()) {
			try {
				entry.getValue().executeCommand("{ ping: 1 }");
				statuses.put(entry.getKey(), true);
			} catch (RuntimeException exception) {
				statuses.put(entry.getKey(), false);
			}
		}
		return statuses;
	}

	public Set<String> shardIds() {
		return new TreeSet<>(templates.keySet());
	}

	@Override
	public void close() {
		clients.values().forEach(MongoClient::close);
	}
}
