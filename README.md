
## How it works

**1. Link transformation** (`POST /links`)
Called once per outbound email, before it is sent. It finds every `<a href="...">` link, makes an 8-character code for each one, and saves the code-to-URL mapping in MySQL and Redis. If two codes ever collide (very unlikely), it tries again with a new code, up to 5 times, instead of failing the whole request.

**2. Redirect** (`GET /r/:code`)
Called when someone clicks a link. The code is checked against the expected format first, so a bad code never triggers a Redis or MySQL lookup. A valid code is looked up in Redis first, then MySQL if it is not cached (and Redis is refilled for next time). There are three outcomes, each with its own page:
- code missing or badly formed: 404
- Redis and MySQL both down: 503, logged, with a friendly "temporarily unavailable" page
- success: an immediate 302 redirect, with `Cache-Control: no-store` so the response is never cached (a 301 would let browsers skip the server on repeat clicks, breaking tracking)

Click logging runs on a background thread after the redirect is sent. It never delays the response, and a failure to log is caught and logged instead of silently lost.

**3. Click drain** (background, not an HTTP endpoint)
`ClickDrainService` starts when the app starts and runs on its own. It reads from SQS, saves each click to MySQL, then deletes the message. If a read fails (SQS down, bad message), it waits 5 seconds before trying again instead of retrying in a tight loop. This matters because the deployed version has no SQS queue set up, so this failure path runs by default, not just in theory (see "Considered but not implemented").

## Engineering decisions

- **Redis first, MySQL as backup.** Most clicks are answered by one fast Redis read. MySQL stays the source of truth.
- **Redis failures fall back instead of failing the request.** `RedisClient` catches connection errors and returns `null`, which the rest of the code already treats as "not cached."
- **Timeouts on Redis and MySQL** (about 1 to 2 seconds) so a stuck dependency fails fast instead of holding a request open for 30+ seconds. A slow dependency is worse for the user than a down one, since nothing forces a fallback without a timeout.
- **404 and 503 mean different things.** A missing or bad code is normal traffic and is not logged as an error. A dependency outage is not normal, gets logged, and is the case that should page someone.
- **`ResolutionException` only covers real database failures**, not any `RuntimeException`. An unrelated bug should not be reported as "Redis and MySQL are down."
- **Code generation retries on collision** instead of failing the whole request, even though a real collision is very unlikely.
- **Click logging runs on virtual threads**, with failures logged. SQS never blocks the redirect, and a failed log is now visible instead of disappearing.
- **The click drain loop waits after a failure** instead of retrying right away, so a queue that is down for good (the real state here) does not spin the CPU or flood the logs.

## Considered but not implemented

- **A real SQS queue in production.** The deployed app runs without one. Click logging failures are already caught and logged, so this is a choice, not an oversight: one less credential to manage for a feature outside the focus area, and it doubles as a live test of the graceful-failure design.
- **SQS dead-letter queue.** `ClickDrainService` has no protection against a bad message being redelivered forever. A dead-letter queue (a second queue plus a redrive rule) is the standard fix, and SQS handles it without app code changes. Designed, not built, due to time.
- **Virtual threads on the redirect path itself.** Used only for click logging so far. Extending it to the redirect method itself risks the most tested part of the app for a gain a local benchmark cannot show.

## Scaling

- **The app is stateless.** All state lives in MySQL, Redis, and SQS, so it can run behind a load balancer with no code changes.
- **Redis** should move to a managed setup with replicas (like ElastiCache) for high availability.
- **MySQL** connection pool size should match real traffic, and read replicas should be considered for the redirect path at high volume. An index on `clicks.code` will matter once anyone aggregates by code.
- **SQS** already keeps click logging off the main path. At scale, a dead-letter queue stops one bad message from blocking the drain loop, and more than one `ClickDrainService` can run at once with no extra setup. Its HTTP connection pool also needs sizing: the default of 50 caused timeouts at 100 connections at once during testing, and raising it to 200 fixed that.
- **Logging exists on the failure paths**, but running this for real needs real metrics: redirect latency, cache hit rate, and queue depth.
- **Putting the redirect endpoint closer to users (a CDN or edge network)** would cut round-trip time more than any backend change.

## Benchmarks

### Local

`sbt run`, dev mode, JVM warmed (first requests after startup are slower due to JIT compilation; these numbers are steady-state, not cold-start). A packaged build would do better still, since dev mode adds overhead that production does not have.

- **Medium load** (`wrk -t2 -c20 -d10s --latency`): 24,498 req/sec, zero errors

| Percentile | Latency |
|---|---|
| p50 | 0.73ms |
| p75 | 0.89ms |
| p90 | 1.08ms |
| p99 | 8.98ms |
| max | 56.33ms |

- **Heavy load** (`wrk -t4 -c100 -d30s --latency`): 23,268 req/sec, zero errors

| Percentile | Latency |
|---|---|
| p50 | 3.76ms |
| p75 | 6.24ms |
| p90 | 9.60ms |
| p99 | 22.21ms |
| max | 92.50ms |

Both runs hit a single code repeatedly, so this measures best-case redirect speed on a guaranteed Redis cache hit, not cache-miss/MySQL-fallback behavior or key diversity under load. Throughput held steady from medium to heavy load (24.5k → 23.3k req/sec) with latency scaling predictably by connection count, showing no sign of contention or GC pressure up to 100 concurrent connections.

### Production (measured on a live Railway deployment)

(`wrk -t2 -c20 -d10s --latency` against the deployed app, redirect endpoint): 91.98 req/sec

| Percentile | Latency |
|---|---|
| p50 | 207.76ms |
| p75 | 209.92ms |
| p90 | 215.45ms |
| p99 | 295.80ms |

This run came after fixing a Redis login bug (the app was missing its Redis password, so every request was quietly falling back to MySQL, caught through Railway's own Redis metrics showing no traffic). After the fix, Redis is confirmed working: 948 `GET` calls at 1.9µs average. But p50 barely changed (220.63ms to 207.76ms), because at this scale the floor is network time to Railway, not backend work, and no backend change can fix that. What did change is the tail: p99 dropped from 480.65ms to 295.80ms, because a working Redis is more consistent than MySQL under load, even if it cannot beat the network floor. This backs up the point above about moving the redirect closer to users.

**Redis-down resilience** was checked in practice, not with `wrk`. Redis was down for a real stretch during development, by accident, and every redirect in that window still worked through the MySQL fallback. That is a stronger result than a planned test would have been.

## Testing

Unit tests (JUnit + Mockito) cover the logic behind the focus area. No real MySQL, Redis, or SQS is needed, dependencies are mocked:
- `RedirectService`: cache hit, cache miss with MySQL fallback, code not found, dependency outage (`ResolutionException`), and the check that keeps a real bug from being reported as an outage.
- `RedirectController`: all three HTTP responses (404, 503, 302).
- `LinkController`: a successful transform, and the check for an empty body.
- `LinkTransformService`: retrying on a code collision, including giving up after too many tries.
- `ClickLoggingService`: the SQS message sent has the right code and timestamp.
- `ClickDrainService`: a click is only deleted from SQS after it is saved to MySQL, never before. So a crash mid-process means the message is redelivered, not lost.

Run the suite with:
```bash
sbt test
```

## Running locally

**Recommended:** Docker Compose for MySQL, Redis, and LocalStack (SQS) together:
```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=eu-west-1

docker-compose up -d
aws --endpoint-url=http://localhost:4566 --region eu-west-1 sqs create-queue --queue-name click-events
SQS_ENDPOINT_OVERRIDE=http://localhost:4566 sbt run
```
*These variables point the app at LocalStack instead of real AWS, for local use only.*

## Usage

**Transform an email:**
```bash
curl -X POST http://localhost:9000/links \
  -H "Content-Type: text/html" \
  --data '<a href="https://example.com/tours/reykjavik-golden-circle">View your tour</a>'
```
Returns the same HTML with the link replaced by a managed URL, for example `http://localhost:9000/r/aZ3kQ9pL`.

**Follow a managed link:**
```bash
curl -i http://localhost:9000/r/YOUR_CODE
```
Replace `YOUR_CODE` with the code returned by the POST above. Returns a 302 redirect to the original page.

## AI-assistance disclosure

This was my first real project using Play Framework, Redis and AWS SQS. An AI assistant (Claude) was used throughout to:

- Explain fundamentals (Play routing and body parsers, Redis and SQS basics)
- Talk through design choices (whether reverse routing or a full virtual-thread rewrite were worth it)
- Review already-written code to spot problems (unhandled Redis failures, missing timeouts, an overly broad catch block)
- Help debug (a MySQL login plugin issue, a Redis authentication bug found in production, an undersized SQS connection pool causing timeouts under load)

Every change was applied by the author, following the assistant's explanations rather than pasting in generated code.