package com.raghu.crm.systemdesign.id;

import java.time.Clock;
import java.time.Instant;

public final class SnowflakeIdGenerator {

	private static final long CUSTOM_EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
	private static final long WORKER_ID_BITS = 10L;
	private static final long SEQUENCE_BITS = 12L;
	private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
	private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
	private static final long MAX_TIMESTAMP = (1L << 41L) - 1;
	private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
	private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

	private final long workerId;
	private final Clock clock;

	private long lastTimestamp = -1L;
	private long sequence;

	public SnowflakeIdGenerator(long workerId) {
		this(workerId, Clock.systemUTC());
	}

	SnowflakeIdGenerator(long workerId, Clock clock) {
		if (workerId < 0 || workerId > MAX_WORKER_ID) {
			throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
		}
		this.workerId = workerId;
		this.clock = clock;
	}

	public synchronized long nextId() {
		long timestamp = clock.millis();

		if (timestamp < lastTimestamp) {
			throw new IllegalStateException(
				"System clock moved backwards by " + (lastTimestamp - timestamp) + " ms");
		}

		if (timestamp == lastTimestamp) {
			sequence = (sequence + 1) & MAX_SEQUENCE;
			if (sequence == 0) {
				timestamp = waitForNextMillisecond();
			}
		} else {
			sequence = 0;
		}

		long timestampPart = timestamp - CUSTOM_EPOCH;
		if (timestampPart < 0 || timestampPart > MAX_TIMESTAMP) {
			throw new IllegalStateException("Current time is outside the supported ID timestamp range");
		}

		lastTimestamp = timestamp;
		return (timestampPart << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
	}

	private long waitForNextMillisecond() {
		long timestamp = clock.millis();
		while (timestamp <= lastTimestamp) {
			Thread.onSpinWait();
			timestamp = clock.millis();
		}
		return timestamp;
	}
}
