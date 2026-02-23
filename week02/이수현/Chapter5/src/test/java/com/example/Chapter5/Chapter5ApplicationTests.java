package com.example.Chapter5;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT) // 8080 사용
class ConcurrencyApplyTest {

	@Test
	void concurrent_apply_experiment() throws Exception {
		long eventId = 1L;

		// 실험 파라미터 바꿔가며 반복
		String mode = "COUNT_BASED"; // FOR_UPDATE | ATOMIC_UPDATE | COUNT_BASED
		String iso = "RC";           // RR | RC

		int concurrency = 200;       // 50/200/1000
		int poolSize = 50;

		HttpClient client = HttpClient.newHttpClient();
		ExecutorService pool = Executors.newFixedThreadPool(poolSize);

		// 동시에 시작하도록 barrier 사용
		CyclicBarrier barrier = new CyclicBarrier(concurrency);

		AtomicInteger ok = new AtomicInteger();
		AtomicInteger fail = new AtomicInteger();

		List<Callable<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			long userId = i + 1;
			tasks.add(() -> {
				barrier.await(); // 모두 여기서 대기하다가 동시에 출발

				URI uri = URI.create("http://localhost:8080/events/" + eventId + "/apply"
								+ "?userId=" + userId
								+ "&mode=" + mode
								+ "&iso=" + iso);

				HttpRequest req = HttpRequest.newBuilder(uri)
								.POST(HttpRequest.BodyPublishers.noBody())
								.build();

				try {
					HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
					if (res.statusCode() == 200 && res.body().contains("\"ok\":true")) ok.incrementAndGet();
					else fail.incrementAndGet();
				} catch (Exception e) {
					fail.incrementAndGet();
				}
				return null;
			});
		}

		// 실험 전 reset (API 호출)
		resetEvent(client, eventId);

		long start = System.currentTimeMillis();
		List<Future<Void>> futures = pool.invokeAll(tasks);
		for (Future<Void> f : futures) f.get();
		long took = System.currentTimeMillis() - start;

		pool.shutdown();
		pool.awaitTermination(30, TimeUnit.SECONDS);

		String stats = getStats(client, eventId);

		System.out.println("mode=" + mode + ", iso=" + iso +
						", concurrency=" + concurrency +
						", ok=" + ok.get() + ", fail=" + fail.get() +
						", tookMs=" + took);
		System.out.println("stats=" + stats);
	}

	private static void resetEvent(HttpClient client, long eventId) throws Exception {
		URI uri = URI.create("http://localhost:8080/events/" + eventId + "/reset");
		HttpRequest req = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
		client.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private static String getStats(HttpClient client, long eventId) throws Exception {
		URI uri = URI.create("http://localhost:8080/events/" + eventId + "/stats");
		HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
		return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
	}
}
