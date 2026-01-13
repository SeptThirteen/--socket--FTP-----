# ExecutorService & newFixedThreadPool - 新手入门指南 🚀

## 简介

在 Java 中，`ExecutorService` 提供了一个高级抽象来管理并发任务。使用线程池可以避免直接创建/管理 `Thread`，重用线程以降低资源开销，并更容易管理并发行为。

本指南面向初学者，讲清楚如何使用 `Executors.newFixedThreadPool`、如何提交任务、如何优雅关闭线程池、常见陷阱与推荐实践，并给出针对服务器（比如 FTP）的示例。

---

## 1. 几个核心概念（简明）

- `Runnable`：可运行的任务，只有 `void run()` 方法
- `Callable<V>`：可返回结果的任务，`V call()`，可抛异常
- `ExecutorService`：线程池接口，负责接收并执行任务
- `Executors`：常用工厂方法（`newFixedThreadPool` / `newCachedThreadPool` / `newSingleThreadExecutor`）
- `Future<V>`：表示异步任务的结果，可以 `get()`（阻塞/带超时）

---

## 2. 创建固定大小线程池（最常用）

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

int POOL_SIZE = 4;
ExecutorService threadPool = Executors.newFixedThreadPool(POOL_SIZE);
```

意义：线程池固定创建 `POOL_SIZE` 个线程，重复复用。多余任务会被放入内部队列等待执行。

---

## 3. 提交任务：execute vs submit

- `execute(Runnable)`：不返回结果，抛出运行时异常会在线程池内部处理（未返回给调用者）
- `submit(Runnable)`：返回 `Future<?>`，可以通过 `future.get()` 等待或检测异常
- `submit(Callable<V>)`：返回 `Future<V>`，可获取返回值或异常

示例：

```java
threadPool.execute(() -> System.out.println("hello"));

Future<Integer> f = threadPool.submit(() -> {
    Thread.sleep(1000);
    return 42;
});
int result = f.get(); // 阻塞直到完成
```

---

## 4. 优雅关闭线程池（非常重要）

```
threadPool.shutdown(); // 停止接收新任务，等待正在执行任务完成
if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
    threadPool.shutdownNow(); // 尝试中断正在执行的任务并返回未执行的任务列表
}
```

注意：`shutdownNow()` 会尝试中断任务，因此任务中应对中断做友好处理。

---

## 5. 拒绝策略与有界队列（生产环境推荐）

`Executors.newFixedThreadPool` 默认使用无界队列（`LinkedBlockingQueue`），在流量突增时可能导致内存耗尽。生产环境建议手动构建 `ThreadPoolExecutor`，使用有界队列并配置拒绝策略。

示例：有界队列 + 抛弃策略或抛出异常

```java
import java.util.concurrent.*;

int POOL_SIZE = 32;
int QUEUE_CAPACITY = 100;
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
    Executors.defaultThreadFactory(),
    new ThreadPoolExecutor.AbortPolicy() // 超出时抛出 RejectedExecutionException
);
```

当 `submit()` 或 `execute()` 被拒绝时会抛出 `RejectedExecutionException`，服务器应捕获并合理处理（例如关闭或返回错误给客户端）。

---

## 6. 针对服务器（如 FTP）的示例：有界线程池 + 拒绝处理

下面是一个简单的接受连接并提交任务的模式：当线程池队列已满并被拒绝时，向客户端回复 421 并关闭 socket。

```java
ServerSocket server = new ServerSocket(2121);
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
    new ThreadPoolExecutor.AbortPolicy()
);

while (true) {
    Socket client = server.accept();
    try {
        ClientSession session = new ClientSession(client, userStore);
        pool.execute(session);
    } catch (RejectedExecutionException rex) {
        // 线程池满，拒绝服务：通知客户端并关闭连接
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
            w.write("421 Too many connections, try later\r\n");
            w.flush();
        } catch (IOException ignored) {}
        try { client.close(); } catch (IOException ignored) {}
    } catch (IOException e) {
        try { client.close(); } catch (IOException ignored) {}
    }
}
```

这种方式可以保护服务器内存与系统资源，避免大量未处理的 socket 导致系统崩溃。

---

## 7. 监控与调优

可将 `ExecutorService` 强转为 `ThreadPoolExecutor` 来获取运行时数据：

```java
if (pool instanceof ThreadPoolExecutor) {
    ThreadPoolExecutor t = (ThreadPoolExecutor) pool;
    System.out.println("active=" + t.getActiveCount() + " queued=" + t.getQueue().size());
}
```

通过这些指标调整 `POOL_SIZE` 和 `QUEUE_CAPACITY`。

---

## 8. 常见问题与最佳实践 ✅

- ✅ **优先使用有界队列**，并定义拒绝策略（不要使用默认无界队列在生产服务器中）。
- ✅ **对拒绝情况做明确处理**（记录、给客户端返回错误并关闭 socket）。
- ✅ **优雅关闭**：在应用停止时先 `shutdown()`，等待一段时间，再 `shutdownNow()`。
- ✅ **任务应响应中断**（在长时间任务或阻塞操作中检查 `Thread.interrupted()`）。
- ✅ **指定线程工厂**以便给线程命名，便于排查问题。

---

## 9. 小结

- `newFixedThreadPool` 简单易用，适合并发数可预测的场景。
- 在生产系统中，请使用 `ThreadPoolExecutor` 自定义有界队列和拒绝策略以提高可靠性。
- 始终实现对拒绝、超时与优雅关闭的处理。

---

## 参考资料

- Oracle JDK 并发教程：https://docs.oracle.com/javase/tutorial/essential/concurrency/
- ThreadPoolExecutor javadoc：https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html

---

*文档生成时间：2026-01-09*
