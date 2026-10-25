# Reproducing the scalability experiment

Requirements: Java 21, Docker Compose, Python 3, and JMeter 5.6.3. Matplotlib is optional for report figures; numeric tables do not require it. The frontend requires no Node build. Node is only used for its small JavaScript helper tests.

Use the same signing secret on every application replica. This checkout already has ignored `.local/jwt.properties`. On a fresh clone:

```sh
mkdir -p .local
umask 077
printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 32)" > .local/jwt.properties
```

Never put that file or generated benchmark tokens in Git. The fixture tool and runner operate only on the local laboratory databases. Stop the native development application before the full backend suite; benchmark seeding uses its own worker ID 90.

```sh
docker compose up -d
RUN_INTEGRATION_TESTS=true ./mvnw -B -ntp package
docker compose -f compose.yaml -f compose.scale.yaml build app-1
python3 -m unittest discover -s benchmarks -p 'test_*.py'
node --test benchmarks/frontend.test.mjs
python3 -u benchmarks/run.py --duration 20 --repeats 2
python3 benchmarks/analyze.py
```

The runner starts the baseline app/gateway, measures one active JVM at 5/20/60 users twice, starts two more JVMs and the scaled gateway, and repeats those workloads. It then grows its dataset from 200 to 3,000 synthetic customers, measures growth, stops app-2 during a workload, stops/restarts shard 2, and stops/restarts Redis. **These experiments temporarily interrupt local services.** They do not remove Docker volumes or modify ordinary customer records.

The runner raises API/login allowances for capacity measurements and adds its synthetic staff email to the application containers' allowlist. Its tokens are generated from the existing local signing secret and have the same 15-minute expiry as normal sessions; login/password hashing is outside the timed mix. Its `.local/benchmark-users.csv` is deleted at the end. Signing keys and tokens are not passed in shell arguments or retained in tracked result files.

Artifacts:

- `crm.jmx`: generated, readable JMeter plan; open in JMeter for inspection.
- `results/*-c*-r*.json`: individual trial metrics and Docker CPU/memory snapshots.
- `results/*.jtl.gz`: complete timing/status samples, including warm-up. No authorization headers or response bodies.
- `results/summary.json`: run metadata and per-trial summaries.
- `results/comparison.csv`, `.json`: pooled comparison at all three concurrency levels.
- `results/scalability.png`, `.svg`: plots generated from retained data.
- `results/shard-failure.json`, `redis-failure.json`: fault observations and recovery timing.
- `docs/performance.md`: generated numeric report, methodology, and limitations.

Results are retained rather than invented or replaced by estimates. Do not append a new run to an existing `.jtl`. Move prior evidence to an archive directory and remove leftover incomplete `.jtl` files before rerunning the same names. A short `pilot` run is retained separately and excluded from the final comparison.

The fixture manifest is `.local/benchmark-fixtures.json`. Cleanup reads its generated IDs and removes only those customers and their associated records/index/cache entries:

```sh
python3 benchmarks/run.py --cleanup
```

After cleanup, remove the synthetic staff email and restore ordinary API limits by recreating the application containers with normal settings:

```sh
docker compose -f compose.yaml -f compose.scale.yaml up -d app-1 app-2 app-3 gateway
docker compose -f compose.yaml -f compose.scale.yaml stop baseline-gateway
```

Open `http://127.0.0.1:8090` for the three-process UI. Direct local API ports are 8081/8082/8083; baseline gateway is 8091. The ordinary native Spring Boot run remains available separately on port 8080. The lab configuration binds all published ports to loopback.

The three MongoDB instances and Redis remain single points of failure. Adding app replicas is supported; changing the live shard ring requires migrating existing data first. See [architecture](../docs/architecture.md) and [measured results](../docs/performance.md).
