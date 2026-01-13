# Java FTP 课设 - Day1 详细指导（初学者版）

## 今日目标
搭好基础项目框架，实现一个能**接收连接、回复欢迎码 220、支持 USER/PASS/QUIT 命令、维护登录状态**的简化 FTP 服务器。

---

## 前置知识补充

### 关键概念速成

| 概念 | 说明 | 本项目用途 |
|------|------|----------|
| **TCP Socket** | 两台计算机网络通信的"管道" | 服务器与客户端建立连接 |
| **ServerSocket** | 服务器监听端口的工具 | 服务器监听 2121 端口等待客户端连接 |
| **Socket** | 单条连接 | 每个客户端连接对应一个 Socket |
| **Thread（线程）** | 程序内部"并行执行"的单位 | 同时处理多个客户端（一个线程处理一个客户端） |
| **BufferedReader/Writer** | 字符流读写工具 | 以行为单位读取/发送 FTP 命令 |
| **Map** | 键值对数据结构 | 存储用户名和密码 |

### 所需 Java 特性（你需要了解）
- **基础类型与引用类型**：`String`、`int`、`boolean`
- **异常处理**：`try-catch-finally`、`throws IOException`
- **集合**：`HashMap`、`Hashmap.containsKey()`、`HashMap.get()`
- **多线程**：`Runnable` 接口、`ExecutorService`、`Executors.newFixedThreadPool()`
- **IO 流**：`BufferedReader`、`BufferedWriter`、字符编码 UTF-8

---

## 项目结构

创建如下目录与文件结构：

```
基于socket的FTP设计与实现/
├── DAY1_GUIDE.md                    # 本指导文档
├── src/
│   ├── FtpServer.java               # 服务器入口，监听端口
│   ├── ClientSession.java           # 处理单个客户端连接的会话类
│   └── UserStore.java               # 用户表管理类
├── data/                            # 虚拟 FTP 根目录（存放要传输的文件）
│   ├── test.txt
│   └── (其他文件)
└── README.md                        # 最终的项目说明文档
```

**创建步骤**：
1. 在 `基于socket的FTP设计与实现` 目录下新建 `src` 文件夹。
2. 在 `基于socket的FTP设计与实现` 目录下新建 `data` 文件夹（作为虚拟根目录）。
3. 在 `src` 下分别新建 3 个 `.java` 文件。

---

## 代码详解与实现

### 第 1 步：创建 `UserStore.java` —— 用户表管理器

**目的**：集中管理用户名和密码。

**文件位置**：`src/UserStore.java`

**代码**：
```java
import java.util.HashMap;
import java.util.Map;

/**
 * 用户存储管理器
 * 维护一个用户名 → 密码的映射表
 */
public class UserStore {
    // Map 存储用户名和对应的密码
    private final Map<String, String> users = new HashMap<>();

    /**
     * 构造方法：初始化默认用户
     */
    public UserStore() {
        // 添加默认用户：用户名 alice，密码 123456
        users.put("alice", "123456");
        // 添加默认用户：用户名 bob，密码 abcdef
        users.put("bob", "abcdef");
    }

    /**
     * 检查用户名是否存在
     * 
     * @param username 用户名
     * @return true 存在，false 不存在
     */
    public boolean userExists(String username) {
        return users.containsKey(username);
    }

    /**
     * 校验用户密码是否正确
     * 
     * @param username 用户名
     * @param password 密码
     * @return true 密码正确，false 密码错误或用户不存在
     */
    public boolean authenticate(String username, String password) {
        // 如果用户不存在，直接返回 false
        if (!users.containsKey(username)) {
            return false;
        }
        // 获取存储的密码，与传入的密码比较
        String storedPassword = users.get(username);
        return storedPassword.equals(password);
    }

    /**
     * 工厂方法：创建一个新的 UserStore 实例
     * 这样可以更灵活地创建对象
     */
    public static UserStore create() {
        return new UserStore();
    }
}
```

**关键点解释**：
- **HashMap**：用 `key→value` 存储用户名→密码的对应关系。
- **containsKey()**：检查某个 key 是否存在，返回 `boolean`。
- **get(username)**：根据用户名取出密码。
- **equals()**：比较两个字符串是否相同。

---

### 第 2 步：创建 `ClientSession.java` —— 会话处理器

**目的**：处理**单个客户端**的控制连接，维护登录状态，解析命令。

**文件位置**：`src/ClientSession.java`

**代码**（完整版）：

```java
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 客户端会话处理器
 * 
 * 每当一个客户端连接到 FTP 服务器时，就创建一个 ClientSession 实例
 * 这个实例在一个单独的线程中运行，负责：
 * 1. 与该客户端通过 Socket 进行通信
 * 2. 读取客户端的 FTP 命令
 * 3. 维护登录状态和其他会话数据
 * 4. 发送响应码和消息给客户端
 */
public class ClientSession implements Runnable {
    
    // ==================== 成员变量 ====================
    
    /** 与客户端通信的 Socket */
    private final Socket controlSocket;
    
    /** 从 Socket 读取数据的 Reader（一行一行地读） */
    private final BufferedReader in;
    
    /** 向 Socket 写入数据的 Writer（一行一行地写） */
    private final BufferedWriter out;
    
    /** 用户表管理器 */
    private final UserStore userStore;
    
    // ==================== 会话状态 ====================
    
    /** 是否已认证（已登录）*/
    private boolean authenticated = false;
    
    /** 当前登录的用户名（未登录时为 null）*/
    private String currentUser = null;
    
    // ==================== 构造方法 ====================
    
    /**
     * 初始化一个会话
     * 
     * @param controlSocket 与客户端连接的 Socket
     * @param userStore 用户表管理器
     * @throws IOException 如果 Socket 读写出错
     */
    public ClientSession(Socket controlSocket, UserStore userStore) throws IOException {
        this.controlSocket = controlSocket;
        this.userStore = userStore;
        
        // 从 Socket 获取输入流和输出流
        // InputStreamReader 将字节流转换为字符流
        // BufferedReader 提供按行读取的便利
        InputStream socketInput = controlSocket.getInputStream();
        this.in = new BufferedReader(
            new InputStreamReader(socketInput, StandardCharsets.UTF_8)
        );
        
        // 同理，BufferedWriter 提供按行写的便利
        OutputStream socketOutput = controlSocket.getOutputStream();
        this.out = new BufferedWriter(
            new OutputStreamWriter(socketOutput, StandardCharsets.UTF_8)
        );
    }
    
    // ==================== 核心方法 ====================
    
    /**
     * 线程运行方法
     * 当这个会话对象被提交给线程执行时，此方法会被调用
     * 
     * 整个生命周期：
     * 1. 发送欢迎码 220
     * 2. 循环读取客户端命令并处理
     * 3. 处理 QUIT 时退出循环，关闭连接
     */
    @Override
    public void run() {
        try {
            // 设置 Socket 超时时间（毫秒）：300 秒
            // 如果 300 秒内没有收到数据，Socket 会抛出异常
            controlSocket.setSoTimeout(300000);
            
            // 发送欢迎码
            reply(220, "Simple FTP Server Ready");
            
            // 命令处理主循环
            String line;
            while ((line = in.readLine()) != null) {
                // 去掉首尾空白
                line = line.trim();
                
                // 忽略空行
                if (line.isEmpty()) {
                    continue;
                }
                
                // 处理这条命令
                handleCommand(line);
                
                // 如果用户已经下达了 QUIT，则在下一个循环时 in.readLine() 会返回 null
            }
        } catch (IOException e) {
            // 客户端连接关闭或网络错误
            System.out.println("[ClientSession] 客户端" + currentUser + "断开连接: " + e.getMessage());
        } finally {
            // 确保连接被正确关闭
            try {
                controlSocket.close();
            } catch (IOException e) {
                // 忽略关闭时的错误
            }
        }
    }
    
    /**
     * 处理一条 FTP 命令
     * 
     * @param commandLine 整条命令（如 "USER alice"）
     */
    private void handleCommand(String commandLine) {
        try {
            // 按空格拆分：第一个词是命令，剩下的是参数
            // split("\\s+", 2) 表示：按任意个空白分割，最多分 2 段
            // 例如 "USER alice" → ["USER", "alice"]
            // 例如 "STOR  file.txt" → ["STOR", "file.txt"]
            String[] parts = commandLine.split("\\s+", 2);
            String cmd = parts[0].toUpperCase(Locale.ROOT);  // 命令转大写
            String arg = (parts.length > 1) ? parts[1] : "";  // 获取参数，若无则空字符串
            
            // 根据命令类型进行分发处理
            switch (cmd) {
                case "USER":
                    handleUser(arg);
                    break;
                case "PASS":
                    handlePass(arg);
                    break;
                case "QUIT":
                    handleQuit();
                    break;
                case "HELP":
                    handleHelp();
                    break;
                default:
                    // 未知命令
                    reply(500, "Unknown command: " + cmd);
            }
        } catch (Exception e) {
            // 命令处理中出错，发送错误响应
            try {
                reply(500, "Server error: " + e.getMessage());
            } catch (IOException ignored) {
                // 如果发送错误信息也失败了，放弃
            }
        }
    }
    
    // ==================== 命令处理函数 ====================
    
    /**
     * 处理 USER 命令
     * 客户端要求：USER <username>
     * 服务器响应：
     *   - 用户存在 → 331（需要密码）
     *   - 用户不存在 → 530（登录失败）
     */
    private void handleUser(String username) throws IOException {
        // 1. 参数校验
        if (username == null || username.trim().isEmpty()) {
            reply(501, "USER requires an argument");
            return;
        }
        
        username = username.trim();
        
        // 2. 检查用户是否存在
        if (!userStore.userExists(username)) {
            reply(530, "Not a valid user");
            return;
        }
        
        // 3. 用户存在，记录下来，等待 PASS 命令
        currentUser = username;
        authenticated = false;  // 还未通过密码认证
        reply(331, "User name ok, need password");
    }
    
    /**
     * 处理 PASS 命令
     * 客户端要求：PASS <password>
     * 服务器响应：
     *   - 认证成功 → 230（已登录）
     *   - 认证失败或未先发送 USER → 530（登录失败）
     */
    private void handlePass(String password) throws IOException {
        // 1. 参数校验
        if (password == null || password.trim().isEmpty()) {
            reply(501, "PASS requires an argument");
            return;
        }
        
        password = password.trim();
        
        // 2. 检查是否已经接收到 USER 命令
        if (currentUser == null) {
            reply(503, "Login with USER first");
            return;
        }
        
        // 3. 校验密码
        if (userStore.authenticate(currentUser, password)) {
            // 密码正确，认证成功
            authenticated = true;
            reply(230, "User " + currentUser + " logged in");
        } else {
            // 密码错误
            currentUser = null;
            authenticated = false;
            reply(530, "Login incorrect");
        }
    }
    
    /**
     * 处理 QUIT 命令
     * 客户端要求：QUIT
     * 服务器响应：221（再见），然后关闭连接
     */
    private void handleQuit() throws IOException {
        reply(221, "Goodbye");
        // 关闭连接，主循环中 readLine() 会返回 null，自动退出
        controlSocket.close();
    }
    
    /**
     * 处理 HELP 命令（可选）
     * 显示支持的命令列表
     */
    private void handleHelp() throws IOException {
        reply(214, "Commands supported:");
        out.write("  USER <username>  - Login with username\r\n");
        out.write("  PASS <password>  - Provide password\r\n");
        out.write("  QUIT             - Disconnect\r\n");
        out.write("  HELP             - Show this message\r\n");
        out.flush();
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 向客户端发送 FTP 响应
     * FTP 响应格式：<code> <message>\r\n
     * 例如：220 Simple FTP Server Ready\r\n
     * 
     * @param code FTP 响应码（3 位数）
     * @param message 响应消息
     */
    private void reply(int code, String message) throws IOException {
        // 格式化响应：码 + 空格 + 消息 + 换行符
        String response = code + " " + message + "\r\n";
        out.write(response);
        out.flush();
        
        // 可选：打印到服务器日志，便于调试
        System.out.println("[ClientSession] " + currentUser + " <- " + response.trim());
    }
}
```

**关键点解释**：

1. **implements Runnable**：这个类实现了 `Runnable` 接口，意味着它可以在线程中执行。
2. **@Override**：标注，表示下面的方法是从接口/父类重写来的。
3. **BufferedReader/BufferedWriter**：
   - `readLine()`：阻塞式读一行（包括 `\r\n`），直到有数据或连接关闭。
   - `write()`：写字符串。
   - `flush()`：将缓冲区内容真正发送出去。
4. **异常处理**：
   - `throws IOException`：声明这个方法可能抛出 IO 异常。
   - 调用者必须处理它（用 try-catch 或继续声明）。
5. **switch-case**：根据命令类型分发处理。
6. **finally**：确保即使出异常，连接也会被关闭。

---

### 第 3 步：创建 `FtpServer.java` —— 服务器主类

**目的**：启动 FTP 服务器，监听 2121 端口，为每个新连接创建一个 `ClientSession` 并分配一个线程。

**文件位置**：`src/FtpServer.java`

**代码**：

```java
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FTP 服务器主类
 * 
 * 职责：
 * 1. 监听指定端口（2121）
 * 2. 接受客户端连接
 * 3. 为每个连接创建一个 ClientSession 并分配线程处理
 */
public class FtpServer {
    
    /** FTP 控制端口 */
    private static final int CONTROL_PORT = 2121;
    
    /** 线程池：最多同时处理 32 个连接 */
    private static final int POOL_SIZE = 32;
    
    /**
     * 主方法
     */
    public static void main(String[] args) {
        // 1. 创建用户存储管理器
        UserStore userStore = UserStore.create();
        System.out.println("[FtpServer] 用户表已初始化");
        
        // 2. 创建线程池
        // newFixedThreadPool(POOL_SIZE)：创建固定大小的线程池
        // 它可以最多同时运行 32 个任务，超过的任务会排队等候
        ExecutorService threadPool = Executors.newFixedThreadPool(POOL_SIZE);
        System.out.println("[FtpServer] 线程池已创建，容量=" + POOL_SIZE);
        
        try {
            // 3. 创建服务器 Socket，监听 2121 端口
            ServerSocket serverSocket = new ServerSocket(CONTROL_PORT);
            System.out.println("[FtpServer] FTP 服务器启动成功，监听端口 " + CONTROL_PORT);
            System.out.println("[FtpServer] 等待客户端连接...");
            
            // 4. 主循环：接受连接
            int clientCount = 0;
            while (true) {
                // accept() 是阻塞调用，会一直等到有新连接
                Socket clientSocket = serverSocket.accept();
                clientCount++;
                
                String clientAddr = clientSocket.getInetAddress().getHostAddress() + ":" + 
                                    clientSocket.getPort();
                System.out.println("[FtpServer] 客户端 #" + clientCount + " 已连接: " + clientAddr);
                
                try {
                    // 5. 创建会话处理器
                    ClientSession session = new ClientSession(clientSocket, userStore);
                    
                    // 6. 提交给线程池处理（异步）
                    // 这样服务器主线程不会阻塞，可以继续接受其他连接
                    threadPool.submit(session);
                    
                } catch (IOException e) {
                    System.out.println("[FtpServer] 创建会话失败: " + e.getMessage());
                    try {
                        clientSocket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            
        } catch (IOException e) {
            System.err.println("[FtpServer] 服务器启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

**关键点解释**：

1. **ServerSocket(port)**：绑定到某个端口，等待客户端连接。
2. **accept()**：阻塞调用，返回一个 `Socket` 代表新连接。
3. **ExecutorService**：线程池，管理多个线程的执行。
4. **submit(Runnable)**：提交一个任务给线程池；线程池会自动分配空闲线程运行它。
5. **主线程不阻塞**：因为 `ClientSession` 的 `run()` 是在线程池的工作线程中执行，主线程继续循环 `accept()`。

---

## 编译与运行

### Windows 下编译（针对两种常见源码布局）

打开命令行（PowerShell 或 CMD），进入项目根目录：

```powershell
cd "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 创建输出目录（如不存在）
mkdir bin

# 1) 如果你的 Java 源文件位于 src\ 且文件头为 `package data;`（推荐）
javac -d bin -encoding UTF-8 src\*.java

# 2) 如果你的 Java 源文件直接位于 data\（文件头为 `package data;`），请改为：
# javac -d bin -encoding UTF-8 data\*.java
```

**提示**：`-d bin` 会把编译生成的 `.class` 文件输出到 `bin`，并保留包路径（例如 `bin\data\...`）。确保源文件中的 `package` 声明（如 `package data;`）与目录结构一致，否则会导致运行时找不到主类。

### 运行服务器

推荐在项目根目录运行（使用类路径指定 bin）：

```powershell
java -cp bin data.FtpServer
```

或者进入 `bin` 目录再运行（等效）：

```powershell
cd bin
java data.FtpServer
```

**预期输出**：
```
[FtpServer] 用户表已初始化
[FtpServer] 线程池已创建，容量=32
[FtpServer] FTP 服务器启动成功，监听端口 2121
[FtpServer] 等待客户端连接...
```

**常见错误与快速排查**：
- "Could not find or main class" → 通常是因为没有正确设置类路径或使用了错误的类名（记得用 `data.FtpServer` 而不是 `FtpServer`，并确保 `bin` 在 `-cp` 中）。
- "javac: not found" 或 "java 不是内部或外部命令" → Java 未安装或未加入 PATH。
- 编译找不到类 → 检查 `package` 声明与文件夹结构是否一致（例如 `package data;` 应放在 `src\` 或 `data\` 的源码中）。

**此时服务器处于监听状态，等待客户端连接。**

---

## 测试方法

### 方法 1：用 telnet 手打命令（最简单）

在**另一个终端**窗口输入：

```powershell
telnet localhost 2121
```

如果成功连接，会看到：
```
Connected to localhost.
Escape character is '^]'.
220 Simple FTP Server Ready
```

然后可以输入命令：

```
USER alice
# 服务器回复：
# 331 User name ok, need password

PASS 123456
# 服务器回复：
# 230 User alice logged in

HELP
# 服务器回复支持的命令

QUIT
# 服务器回复：
# 221 Goodbye
# 连接关闭
```

**注意**：
- Windows 默认可能没有 telnet，需要在"控制面板 → 程序 → 程序和功能 → 启用或关闭 Windows 功能"中勾选"Telnet Client"。
- 如果没有 telnet，可以用 PowerShell 模拟（见下文）。

### 方法 2：用 PowerShell 脚本测试

创建文件 `test_ftp.ps1`：

```powershell
# 连接到 FTP 服务器
$host = "127.0.0.1"
$port = 2121

$socket = New-Object System.Net.Sockets.TcpClient($host, $port)
$stream = $socket.GetStream()
$reader = New-Object System.IO.StreamReader($stream)
$writer = New-Object System.IO.StreamWriter($stream)
$writer.AutoFlush = $true

# 读取服务器欢迎码
$welcome = $reader.ReadLine()
Write-Host "服务器: $welcome"

# 发送 USER 命令
$writer.WriteLine("USER alice")
$response = $reader.ReadLine()
Write-Host "服务器: $response"

# 发送 PASS 命令
$writer.WriteLine("PASS 123456")
$response = $reader.ReadLine()
Write-Host "服务器: $response"

# 发送 QUIT 命令
$writer.WriteLine("QUIT")
$response = $reader.ReadLine()
Write-Host "服务器: $response"

# 关闭连接
$socket.Close()
```

然后运行：
```powershell
powershell -ExecutionPolicy Bypass -File test_ftp.ps1
```

---

## 常见错误与排查

| 错误 | 原因 | 解决方法 |
|------|------|---------|
| `Address already in use` | 端口 2121 已被占用 | 改用其他端口（如 2122），或用 `netstat -ano \| findstr 2121` 查看占用进程并结束 |
| `cannot find symbol: UserStore` | `UserStore` 类未编译或路径错误 | 确保 `UserStore.java` 在 `src` 下，且 `javac -d bin src/*.java` 编译了它 |
| telnet 连接失败 | Windows 未启用 telnet 或防火墙阻止 | 启用 Windows Telnet 功能，或用 PowerShell 脚本测试 |
| `read timed out` | 服务器超时未回复 | 检查 `reply()` 方法是否调用了 `flush()` |

---

## Debug 技巧

### 在代码中增加日志
在关键位置加 `System.out.println()` 查看执行流：

```java
System.out.println("[DEBUG] 进入 handleUser 方法，用户名=" + username);
System.out.println("[DEBUG] 用户存在=" + userStore.userExists(username));
```

### 观察服务器日志
运行服务器时看到的输出会显示：
- 客户端连接和断开
- 发送的响应码
- 认证状态变化

---

## 检查清单

完成 Day1，你应该能做到：

- [ ] 项目结构正确创建（src、bin、data 目录）
- [ ] 3 个 `.java` 文件编译无误
- [ ] 服务器启动，打印"等待客户端连接"
- [ ] 用 telnet/PowerShell 连接后收到 `220 Simple FTP Server Ready`
- [ ] `USER alice` 收到 `331`（需要密码）
- [ ] `PASS 123456` 收到 `230`（登录成功）
- [ ] `PASS 错误密码` 收到 `530`（失败）
- [ ] `QUIT` 收到 `221`，连接关闭
- [ ] 用户 `bob/abcdef` 也能登录
- [ ] 可以同时连接多个客户端，互不干扰

---

## 下一步预告（Day2）

- 实现 `PWD`（当前目录）和 `CWD <path>`（切换目录）命令
- 维护当前工作目录状态
- 实现路径安全校验（防止 `..` 越权）

---

## 参考资源

- [Java Socket 官方文档](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html)
- [Java BufferedReader/Writer](https://docs.oracle.com/javase/8/docs/api/java/io/BufferedReader.html)
- [Java ExecutorService](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html)
- FTP 协议（RFC 959）—— 可选阅读，了解标准响应码

---

## 常见问题（FAQ）

**Q：为什么要用线程？**  
A：每条控制连接需要独立执行命令，多个客户端同时连接时，如果主线程来处理，其他客户端就得等。线程池解决这个问题。

**Q：为什么要用 BufferedReader 而不是直接读字节？**  
A：FTP 是基于行的文本协议，每条命令以 `\r\n` 结尾。`BufferedReader.readLine()` 自动按行读取，省去自己处理换行符的麻烦。

**Q：UserStore 里的密码是明文存的，安全吗？**  
A：这是课设的简化版本，实际应用中应该对密码进行哈希加密。目前学习阶段可以忽略。

**Q：为什么响应后面要加 `\r\n`？**  
A：FTP 协议要求文本行以 CRLF（`\r\n`）结尾，这样客户端才能正确识别一行的结束。

**Q：如果同一个端口重复运行服务器，为什么报 Address already in use？**  
A：即使上一个进程已退出，操作系统也需要一段时间（TCP TIME_WAIT）才能释放端口，通常 30~120 秒。可以改用其他端口临时测试。

---

**祝你编码顺利！如有疑问，仔细看代码注释和本指南。**
