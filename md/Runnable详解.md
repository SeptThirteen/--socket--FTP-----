# Java Runnable 接口详解

## 📌 基本概念

`Runnable` 是 Java 中的一个**接口**，用于定义**可以被线程执行的任务**。它是 Java 多线程编程的核心之一。

```java
public interface Runnable {
    void run();  // 唯一的方法
}
```

---

## 🎯 核心作用

`Runnable` 的作用就是：**将要执行的代码打包成一个"任务"，交给线程去运行**。

### 为什么需要 Runnable？

在 Java 中，**线程和任务是分离的**：
- **Thread**：执行者（线程）
- **Runnable**：被执行的任务内容

这种设计的好处：
- ✅ 一个类可以同时继承其他类（Java 单继承限制）
- ✅ 代码结构更清晰
- ✅ 任务可以被多个线程共享执行

---

## 💡 在 ClientSession 中的应用

### 第 17 行的含义

```java
public class ClientSession implements Runnable {
```

这表示：**ClientSession 这个类"实现"了 Runnable 接口**，即它定义了一个可以被线程执行的任务。

### 必须实现 run() 方法

```java
@Override
public void run() {
    try {
        // 设置超时时间
        controlSocket.setSoTimeout(300000);
        
        // 发送欢迎码
        reply(220, "Simple FTP Server Ready");
        
        // 循环处理客户端命令
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            handleCommand(line);
        }
    } catch (IOException e) {
        System.out.println("[ClientSession] 客户端断开连接: " + e.getMessage());
    } finally {
        try {
            controlSocket.close();
        } catch (IOException e) {
            // 忽略
        }
    }
}
```

这个 `run()` 方法包含了**处理一个客户端的全部逻辑**。

---

## 🔄 工作流程

### 1️⃣ 创建任务对象

```java
ClientSession session = new ClientSession(socket, userStore);
// 此时 session 是一个 Runnable 对象
```

### 2️⃣ 创建线程并绑定任务

```java
Thread thread = new Thread(session);  // 将任务传给线程
```

### 3️⃣ 启动线程

```java
thread.start();  // 线程启动后会自动调用 session.run()
```

---

## 📊 完整示例

### 在 FTP 服务器中的实际应用

```java
public class FtpServer {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(21);
        UserStore userStore = new UserStore();
        
        // 服务器循环，等待客户端连接
        while (true) {
            Socket clientSocket = serverSocket.accept();  // 新客户端连接
            
            // 为这个客户端创建一个会话对象（Runnable 任务）
            ClientSession session = new ClientSession(clientSocket, userStore);
            
            // 创建新线程，执行这个任务
            Thread thread = new Thread(session);
            thread.start();  // 线程启动，run() 方法被调用
            
            // 服务器继续循环，等待下一个客户端
            // ✅ 这样多个客户端可以同时被处理
        }
    }
}
```

---

## 🔀 Runnable vs Thread

| 特性 | Thread | Runnable |
|------|--------|----------|
| **类型** | 类 | 接口 |
| **继承** | 直接继承 Thread | 实现 Runnable |
| **灵活性** | 不能继承其他类 ❌ | 可以继承其他类 ✅ |
| **推荐度** | 不推荐 | **推荐** ✅ |

### ❌ 不推荐的方式（直接继承 Thread）

```java
public class MyTask extends Thread {
    public void run() {
        // 任务代码
    }
}

MyTask task = new MyTask();
task.start();  // 直接启动
```

**问题**：如果 MyTask 已经继承了其他类，就无法再继承 Thread。

### ✅ 推荐的方式（实现 Runnable）

```java
public class MyTask implements Runnable {
    public void run() {
        // 任务代码
    }
}

Thread thread = new Thread(new MyTask());
thread.start();
```

**优点**：可以同时继承其他类。

---

## 🎬 执行流程演示

```
主线程                          工作线程（ClientSession）
   |
   ├─ serverSocket.accept()
   |      ↓
   ├─ 收到客户端连接
   |      ↓
   ├─ new ClientSession(socket, userStore)  [Runnable 对象]
   |      ↓
   ├─ new Thread(session)                    [创建线程]
   |      ↓
   ├─ thread.start()                         [启动线程]
   |      ↓
   ├─ 继续循环等待下一个客户端  ─────────→  session.run() 执行
   |                               ↓
   |                          读取客户端数据
   |                               ↓
   |                          处理 USER/PASS 命令
   |                               ↓
   |                          接收 QUIT 命令
   |                               ↓
   |                          关闭连接，线程结束
```

---

## 📋 Runnable 的生命周期

### 1. 创建阶段
```java
ClientSession session = new ClientSession(socket, userStore);
// Runnable 对象被创建，但 run() 方法尚未执行
```

### 2. 就绪阶段
```java
Thread thread = new Thread(session);
// 线程对象被创建，但还未启动
```

### 3. 运行阶段
```java
thread.start();
// 线程启动，run() 方法开始执行
```

### 4. 结束阶段
```
// 当 run() 方法执行完毕或抛出异常时，线程自动结束
```

---

## 🎯 ClientSession 中的关键点

| 行为 | 代码位置 | 含义 |
|------|---------|------|
| 定义任务 | `implements Runnable` | 声明这是一个可执行的任务 |
| 实现任务 | `public void run()` | 定义线程执行的具体代码 |
| 线程安全 | 各成员变量 private | 每个会话独立，互不干扰 |
| 持久化连接 | `while ((line = in.readLine()) != null)` | 持续处理客户端命令，直到断开 |
| 优雅关闭 | `finally { controlSocket.close(); }` | 确保资源释放 |

---

## 🔑 关键代码解析

### run() 方法的结构

```java
@Override
public void run() {
    try {
        // ✅ 初始化：设置超时
        controlSocket.setSoTimeout(300000);
        
        // ✅ 欢迎：发送欢迎码
        reply(220, "Simple FTP Server Ready");
        
        // ✅ 主循环：处理所有客户端命令
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            handleCommand(line);
        }
    } catch (IOException e) {
        // ✅ 异常处理：客户端断开或网络错误
        System.out.println("[ClientSession] 客户端" + currentUser + "断开连接");
    } finally {
        // ✅ 清理：始终关闭连接
        try {
            controlSocket.close();
        } catch (IOException e) {
            // 忽略
        }
    }
}
```

---

## 💻 实际应用场景

### 场景 1：单个客户端连接

```
FtpServer 主线程          ClientSession 工作线程
      |                          |
      ├─ accept() ─────────────┐ |
      |                        ↓ |
      ├─ new ClientSession() ──→ |
      |                        ↓ |
      ├─ new Thread(session) ──→ |
      |                        ↓ |
      ├─ thread.start() ──────→ run()
      |                   │      ↓
      │                   │   循环读取命令
      │                   │   处理 USER
      │                   │   处理 PASS
      │                   │   ...
```

### 场景 2：多个并发客户端

```
        FtpServer 主线程
              |
        ┌─────┼─────┬──────┐
        ↓     ↓     ↓      ↓
    Thread1 Thread2 Thread3 Thread4
    Client1 Client2 Client3 Client4
    
所有线程并发运行，互不干扰
```

---

## ⚠️ 常见误区

### ❌ 直接调用 run() 方法

```java
ClientSession session = new ClientSession(socket, userStore);
session.run();  // ❌ 错误！这样是在主线程中执行，不是新线程
```

### ✅ 应该使用 start() 方法

```java
ClientSession session = new ClientSession(socket, userStore);
Thread thread = new Thread(session);
thread.start();  // ✅ 正确！这样会在新线程中执行 run()
```

---

## 📚 总结

### 三个核心要点

1. **Runnable 定义任务**
   - 实现 Runnable 接口
   - 重写 run() 方法

2. **Thread 执行任务**
   - new Thread(runnable)
   - thread.start()

3. **并发处理多个客户端**
   - 每个客户端一个线程
   - 互相独立，同时运行

### 记忆口诀

**"Runnable 就是：我有一个任务要执行，请给我一个线程来运行它"**

- 💼 **Runnable**：任务（做什么）
- 🚀 **Thread**：执行者（谁来做）
- ⚡ **start()**：启动（开始做）

---

## 🎓 在 FTP 服务器中的意义

在你的 FTP 服务器中，使用 Runnable 接口的好处：

1. **并发处理**：多个客户端同时连接时，每个客户端都在独立的线程中运行
2. **资源隔离**：每个 ClientSession 维护自己的连接和状态，互不干扰
3. **可扩展性**：可以处理数百甚至数千个并发连接
4. **代码清晰**：任务定义（Runnable）和执行者（Thread）分离，结构清晰

---

## 参考链接

- [Java Thread Documentation](https://docs.oracle.com/javase/tutorial/essential/concurrency/)
- [Runnable Interface](https://docs.oracle.com/javase/8/docs/api/java/lang/Runnable.html)
- [Thread Class](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html)

---

**文档创建时间**：2026-01-09  
**相关文件**：ClientSession.java
