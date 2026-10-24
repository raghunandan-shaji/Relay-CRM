package com.raghu.crm.systemdesign.id;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTests {

	private static final long TEST_TIME = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();

	@Test
	void rejectsInvalidWorkerIds() {
		assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
		assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
	}

	@Test
	void generatesOrderedUniqueIdsOnOneWorker() {
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator(7, new ConstantClock(TEST_TIME));

		long first = generator.nextId();
		long second = generator.nextId();

		assertTrue(second > first);
	}

	@Test
	void differentWorkersDoNotGenerateTheSameId() {
		Clock clock = new ConstantClock(TEST_TIME);

		long first = new SnowflakeIdGenerator(1, clock).nextId();
		long second = new SnowflakeIdGenerator(2, clock).nextId();

		assertTrue(first != second);
	}

	@Test
	void waitsForTheNextMillisecondWhenSequenceIsFull() {
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator(3, new RolloverClock(TEST_TIME));
		Set<Long> ids = ConcurrentHashMap.newKeySet();

		for (int i = 0; i < 4_097; i++) {
			ids.add(generator.nextId());
		}

		assertEquals(4_097, ids.size());
	}

	@Test
	void rejectsClockRollback() {
		MutableClock clock = new MutableClock(TEST_TIME);
		SnowflakeIdGenerator generator = new SnowflakeIdGenerator(4, clock);
		generator.nextId();
		clock.setMillis(TEST_TIME - 1);

		assertThrows(IllegalStateException.class, generator::nextId);
	}

	@Test
	void generatesIdsConcurrentlyAcrossWorkersWithoutCollisions() throws Exception {
		int workerCount = 4;
		int idsPerWorker = 100_000;
		Set<Long> ids = ConcurrentHashMap.newKeySet(workerCount * idsPerWorker);
		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		long startedAt = System.nanoTime();

		try {
			for (int workerId = 0; workerId < workerCount; workerId++) {
				SnowflakeIdGenerator generator = new SnowflakeIdGenerator(workerId);
				executor.submit(() -> {
					for (int i = 0; i < idsPerWorker; i++) {
						ids.add(generator.nextId());
					}
				});
			}
		} finally {
			executor.shutdown();
			assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
		}

		long elapsedNanos = System.nanoTime() - startedAt;
		int expectedIds = workerCount * idsPerWorker;
		long idsPerSecond = Math.round(expectedIds / (elapsedNanos / 1_000_000_000.0));

		assertEquals(expectedIds, ids.size());
		System.out.printf("ID benchmark: %,d IDs, %,d IDs/second, %d collisions%n",
			expectedIds, idsPerSecond, expectedIds - ids.size());
	}

	private static class ConstantClock extends Clock {
		private final long millis;

		private ConstantClock(long millis) {
			this.millis = millis;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis);
		}

		@Override
		public long millis() {
			return millis;
		}
	}

	private static final class MutableClock extends ConstantClock {
		private final AtomicLong currentMillis;

		private MutableClock(long millis) {
			super(millis);
			this.currentMillis = new AtomicLong(millis);
		}

		void setMillis(long millis) {
			currentMillis.set(millis);
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(currentMillis.get());
		}

		@Override
		public long millis() {
			return currentMillis.get();
		}
	}

	private static final class RolloverClock extends ConstantClock {
		private final long initialMillis;
		private final AtomicInteger reads = new AtomicInteger();

		private RolloverClock(long millis) {
			super(millis);
			this.initialMillis = millis;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis());
		}

		@Override
		public long millis() {
			return reads.incrementAndGet() > 4_097 ? initialMillis + 1 : initialMillis;
		}
	}
}
