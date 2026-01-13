# Java FTP 课设 - Day3 详细指导（初学者版）

## 今日目标
实现**数据连接与目录列表功能**，这是 FTP 协议的核心特性：
- 理解 FTP 的**双连接模型**（控制连接 + 数据连接）
- 实现 `PORT` 命令：客户端告知服务器数据端口
- 实现 `LIST` 命令：通过数据连接传输目录列表
- 学习如何建立临时数据连接并正确关闭

---

## 核心概念：FTP 的双连接架构

### 为什么 FTP 需要两条连接？

| 连接类型 | 用途 | 生命周期 | 端口 |
|---------|------|---------|------|
| **控制连接** | 发送命令、接收响应码 | 整个会话期间保持 | 服务器 2121 |
| **数据连接** | 传输文件内容、目录列表 | 临时建立，传输完成后关闭 | 动态协商 |

**设计原因**：
1. **职责分离**：命令和数据分开，不会互相干扰
2. **并发传输**：可以在传输文件时发送中止命令
3. **安全控制**：控制连接有认证，数据连接可以临时建立

### FTP 主动模式（Active Mode）工作流程

```
客户端                                   服务器
  |                                        |
  |--- 控制连接（持久）------------------->|
  |    USER alice                          |
  |<--------------------------------------|
  |    331 Need password                  |
  |                                        |
  |    PASS 123456                         |
  |<--------------------------------------|
  |    230 Login successful               |
  |                                        |
  |--- PORT 127,0,0,1,19,136 ------------>|  (告知数据端口 19*256+136=5000)
  |<--------------------------------------|
  |    200 PORT command ok                |
  |                                        |
  |    LIST                                |  (请求目录列表)
  |<--------------------------------------|
  |    150 Opening data connection        |
  |                                        |
  |<--- 数据连接（临时）-------------------|  (服务器主动连接客户端 5000 端口)
  |    [传输目录列表数据]                  |
  |<--------------------------------------|  (传输完成后立即关闭数据连接)
  |                                        |
  |<--------------------------------------|
  |    226 Transfer complete              |
  |                                        |
```

**关键点**：
1. 客户端先用 `PORT` 命令告诉服务器"我在 X 端口等你"
2. 服务器收到 `LIST` 命令后，**主动连接**客户端的数据端口
3. 传输完成后，数据连接立即关闭
4. 控制连接保持打开，可以继续发送命令

---

## PORT 命令详解

### 命令格式

```
PORT h1,h2,h3,h4,p1,p2
```

**参数含义**：
- `h1.h2.h3.h4`：客户端 IP 地址（点分十进制的逗号版本）
- `p1,p2`：端口号的高字节和低字节

**端口计算公式**：
```
实际端口 = p1 * 256 + p2
```

**示例解析**：

| PORT 命令 | IP 地址 | 端口计算 | 实际端口 |
|-----------|---------|----------|---------|
| `PORT 192,168,1,100,19,136` | 192.168.1.100 | 19×256+136 | 5000 |
| `PORT 127,0,0,1,78,32` | 127.0.0.1 | 78×256+32 | 20000 |
| `PORT 10,0,0,5,0,21` | 10.0.0.5 | 0×256+21 | 21 |

### Java 解析代码

```java
// PORT 127,0,0,1,19,136
String[] parts = arg.split(",");  // ["127", "0", "0", "1", "19", "136"]

// 组装 IP 地址
String ip = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
// ip = "127.0.0.1"

// 计算端口
int port = Integer.parseInt(parts[4]) * 256 + Integer.parseInt(parts[5]);
// port = 19 * 256 + 136 = 5000
```

---

## 项目结构变化

在 Day2 的基础上，修改和新增以下文件：

```
基于socket的FTP设计与实现/
├── md/
│   ├── DAY1_GUIDE.md
│   ├── DAY2_GUIDE.md
│   └── DAY3_GUIDE.md                # 本文档
├── src/
│   ├── FtpServer.java               # 无需修改
│   ├── ClientSession.java           # 需要修改：增加 PORT/LIST 命令
│   ├── UserStore.java               # 无需修改
│   ├── PathValidator.java           # 无需修改
│   └── DataConnection.java          # 新增：数据连接工具类
├── data/
│   ├── test.txt
│   ├── public/
│   │   └── readme.txt
│   └── upload/
│       └── sample.txt
└── README.md
```

---

## 代码实现详解

### 第 1 步：新增 `DataConnection.java` —— 数据连接工具类

**目的**：封装数据连接的建立、数据传输、关闭逻辑。

**文件位置**：`src/DataConnection.java`

**代码**：

```java
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 数据连接工具类
 * 
 * 负责：
 * 1. 主动连接到客户端的数据端口
 * 2. 发送数据（目录列表、文件内容等）
 * 3. 接收数据（上传的文件）
 * 4. 自动关闭连接
 */
public class DataConnection {
    
    /** 数据连接的超时时间（毫秒）*/
    private static final int TIMEOUT = 30000;  // 30 秒
    
    /** 数据 Socket */
    private Socket dataSocket;
    
    /** 输入流 */
    private InputStream inputStream;
    
    /** 输出流 */
    private OutputStream outputStream;
    
    /**
     * 连接到客户端的数据端口（主动模式）
     * 
     * @param address 客户端数据端口地址
     * @throws IOException 如果连接失败
     */
    public void connect(InetSocketAddress address) throws IOException {
        System.out.println("[DataConnection] 正在连接到客户端数据端口: " + address);
        
        // 创建 Socket 并连接
        dataSocket = new Socket();
        dataSocket.setSoTimeout(TIMEOUT);
        dataSocket.connect(address, TIMEOUT);
        
        // 获取输入输出流
        inputStream = dataSocket.getInputStream();
        outputStream = dataSocket.getOutputStream();
        
        System.out.println("[DataConnection] 数据连接已建立");
    }
    
    /**
     * 发送文本数据（如目录列表）
     * 
     * @param text 要发送的文本内容
     * @throws IOException 如果发送失败
     */
    public void sendText(String text) throws IOException {
        if (outputStream == null) {
            throw new IOException("Data connection not established");
        }
        
        // 将文本转换为字节并发送
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        outputStream.write(data);
        outputStream.flush();
        
        System.out.println("[DataConnection] 已发送 " + data.length + " 字节文本数据");
    }
    
    /**
     * 发送二进制数据（如文件内容）
     * 
     * @param data 要发送的字节数组
     * @throws IOException 如果发送失败
     */
    public void sendBytes(byte[] data) throws IOException {
        if (outputStream == null) {
            throw new IOException("Data connection not established");
        }
        
        outputStream.write(data);
        outputStream.flush();
        
        System.out.println("[DataConnection] 已发送 " + data.length + " 字节二进制数据");
    }
    
    /**
     * 从输入流读取数据（用于上传文件，Day5 会用到）
     * 
     * @param buffer 缓冲区
     * @return 读取的字节数，-1 表示流结束
     * @throws IOException 如果读取失败
     */
    public int read(byte[] buffer) throws IOException {
        if (inputStream == null) {
            throw new IOException("Data connection not established");
        }
        return inputStream.read(buffer);
    }
    
    /**
     * 关闭数据连接
     * 
     * 数据传输完成后必须关闭，释放资源
     */
    public void close() {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            // 忽略关闭错误
        }
        
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException e) {
            // 忽略关闭错误
        }
        
        try {
            if (dataSocket != null && !dataSocket.isClosed()) {
                dataSocket.close();
                System.out.println("[DataConnection] 数据连接已关闭");
            }
        } catch (IOException e) {
            // 忽略关闭错误
        }
    }
    
    /**
     * 检查连接是否已建立
     */
    public boolean isConnected() {
        return dataSocket != null && dataSocket.isConnected() && !dataSocket.isClosed();
    }
}
```

**关键点解释**：

1. **connect(InetSocketAddress)**：
   - 主动连接到客户端提供的地址和端口
   - 设置超时时间（30 秒），避免无限等待
   - 获取输入输出流备用

2. **sendText(String)**：
   - 将字符串转换为 UTF-8 字节
   - 写入输出流并刷新
   - 用于发送目录列表

3. **sendBytes(byte[])**：
   - 直接发送字节数组
   - 用于发送文件内容（Day4）

4. **close()**：
   - 依次关闭输入流、输出流、Socket
   - 即使出错也不抛异常（用 try-catch 包裹）

5. **isConnected()**：
   - 检查连接是否有效
   - 用于在发送数据前验证

---

### 第 2 步：修改 `ClientSession.java` —— 增加 PORT 和 LIST 命令

**需要修改的内容**：
1. 增加成员变量：`dataAddress`（存储客户端数据端口）
2. 增加命令处理：`PORT`、`LIST`
3. 在 `handleCommand()` 的 switch 中增加 case
4. 更新 `HELP` 命令

**修改后的代码**：

```java
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 客户端会话处理器（Day3 版本）
 * 
 * 新增功能：
 * - 支持 PORT（设置数据端口）
 * - 支持 LIST（列出目录内容）
 * - 数据连接管理
 */
public class ClientSession implements Runnable {
    
    // ==================== 成员变量 ====================
    
    private final Socket controlSocket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final UserStore userStore;
    private final PathValidator pathValidator;
    
    // ==================== 会话状态 ====================
    
    private boolean authenticated = false;
    private String currentUser = null;
    private String currentWorkingDir = "/";
    
    /** 客户端数据端口地址（由 PORT 命令设置）*/
    private InetSocketAddress dataAddress = null;
    
    // ==================== 构造方法 ====================
    
    public ClientSession(Socket controlSocket, String rootPath, UserStore userStore) 
            throws IOException {
        this.controlSocket = controlSocket;
        this.userStore = userStore;
        this.pathValidator = new PathValidator(rootPath);
        this.currentWorkingDir = "/";
        
        InputStream socketInput = controlSocket.getInputStream();
        this.in = new BufferedReader(
            new InputStreamReader(socketInput, StandardCharsets.UTF_8)
        );
        
        OutputStream socketOutput = controlSocket.getOutputStream();
        this.out = new BufferedWriter(
            new OutputStreamWriter(socketOutput, StandardCharsets.UTF_8)
        );
    }
    
    // ==================== 核心方法 ====================
    
    @Override
    public void run() {
        try {
            controlSocket.setSoTimeout(300000);
            reply(220, "Simple FTP Server Ready");
            
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                handleCommand(line);
            }
        } catch (IOException e) {
            System.out.println("[ClientSession] 客户端 " + currentUser + " 断开连接: " + e.getMessage());
        } finally {
            try {
                controlSocket.close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }
    
    /**
     * 处理一条 FTP 命令
     */
    private void handleCommand(String commandLine) {
        try {
            String[] parts = commandLine.split("\\s+", 2);
            String cmd = parts[0].toUpperCase(Locale.ROOT);
            String arg = (parts.length > 1) ? parts[1] : "";
            
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
                case "PWD":
                    requireAuth(() -> handlePwd());
                    break;
                case "CWD":
                    requireAuth(() -> handleCwd(arg));
                    break;
                case "PORT":
                    requireAuth(() -> handlePort(arg));
                    break;
                case "LIST":
                    requireAuth(() -> handleList());
                    break;
                case "HELP":
                    handleHelp();
                    break;
                default:
                    reply(500, "Unknown command: " + cmd);
            }
        } catch (Exception e) {
            try {
                reply(500, "Server error: " + e.getMessage());
                e.printStackTrace();
            } catch (IOException ignored) {
            }
        }
    }
    
    // ==================== 权限检查工具 ====================
    
    private void requireAuth(IOAction action) throws IOException {
        if (!authenticated) {
            reply(530, "Not logged in");
            return;
        }
        action.execute();
    }
    
    @FunctionalInterface
    private interface IOAction {
        void execute() throws IOException;
    }
    
    // ==================== 命令处理函数 ====================
    
    /**
     * 处理 USER 命令（无变化）
     */
    private void handleUser(String username) throws IOException {
        if (username == null || username.trim().isEmpty()) {
            reply(501, "USER requires an argument");
            return;
        }
        
        username = username.trim();
        
        if (!userStore.userExists(username)) {
            reply(530, "Not a valid user");
            return;
        }
        
        currentUser = username;
        authenticated = false;
        reply(331, "User name ok, need password");
    }
    
    /**
     * 处理 PASS 命令（无变化）
     */
    private void handlePass(String password) throws IOException {
        if (password == null || password.trim().isEmpty()) {
            reply(501, "PASS requires an argument");
            return;
        }
        
        password = password.trim();
        
        if (currentUser == null) {
            reply(503, "Login with USER first");
            return;
        }
        
        if (userStore.authenticate(currentUser, password)) {
            authenticated = true;
            reply(230, "User " + currentUser + " logged in");
        } else {
            currentUser = null;
            authenticated = false;
            reply(530, "Login incorrect");
        }
    }
    
    /**
     * 处理 QUIT 命令（无变化）
     */
    private void handleQuit() throws IOException {
        reply(221, "Goodbye");
        controlSocket.close();
    }
    
    /**
     * 处理 PWD 命令（无变化）
     */
    private void handlePwd() throws IOException {
        reply(257, "\"" + currentWorkingDir + "\" is current directory");
    }
    
    /**
     * 处理 CWD 命令（无变化）
     */
    private void handleCwd(String targetPath) throws IOException {
        if (targetPath == null || targetPath.trim().isEmpty()) {
            reply(501, "CWD requires an argument");
            return;
        }
        
        targetPath = targetPath.trim();
        
        try {
            Path resolvedPath = pathValidator.resolvePath(currentWorkingDir, targetPath);
            
            if (!pathValidator.isValidDirectory(resolvedPath)) {
                reply(550, "Directory does not exist: " + targetPath);
                return;
            }
            
            currentWorkingDir = pathValidator.toVirtualPath(resolvedPath);
            reply(250, "Directory changed to " + currentWorkingDir);
            
        } catch (SecurityException e) {
            reply(550, "Access denied: " + e.getMessage());
        } catch (IOException e) {
            reply(550, "Cannot change directory: " + e.getMessage());
        }
    }
    
    /**
     * 处理 PORT 命令 - 设置客户端数据端口
     * 
     * 命令格式：PORT h1,h2,h3,h4,p1,p2
     * 
     * @param arg 格式为 "h1,h2,h3,h4,p1,p2" 的字符串
     */
    private void handlePort(String arg) throws IOException {
        // 1. 参数校验
        if (arg == null || arg.trim().isEmpty()) {
            reply(501, "PORT requires an argument");
            return;
        }
        
        arg = arg.trim();
        
        try {
            // 2. 解析参数
            // "127,0,0,1,19,136" → ["127", "0", "0", "1", "19", "136"]
            String[] parts = arg.split(",");
            
            if (parts.length != 6) {
                reply(501, "PORT command format: h1,h2,h3,h4,p1,p2");
                return;
            }
            
            // 3. 组装 IP 地址
            String ip = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            
            // 4. 计算端口：port = p1 * 256 + p2
            int p1 = Integer.parseInt(parts[4]);
            int p2 = Integer.parseInt(parts[5]);
            int port = p1 * 256 + p2;
            
            // 5. 端口范围校验
            if (port < 1024 || port > 65535) {
                reply(501, "Invalid port number: " + port);
                return;
            }
            
            // 6. 保存数据端口地址
            dataAddress = new InetSocketAddress(ip, port);
            
            System.out.println("[ClientSession] 客户端数据端口设置为: " + dataAddress);
            reply(200, "PORT command successful");
            
        } catch (NumberFormatException e) {
            reply(501, "Invalid PORT argument: " + e.getMessage());
        } catch (Exception e) {
            reply(501, "PORT command failed: " + e.getMessage());
        }
    }
    
    /**
     * 处理 LIST 命令 - 列出当前目录内容
     * 
     * 工作流程：
     * 1. 检查是否已设置数据端口（PORT 命令）
     * 2. 发送 150 响应（即将打开数据连接）
     * 3. 建立数据连接
     * 4. 读取当前目录的文件列表
     * 5. 通过数据连接发送列表
     * 6. 关闭数据连接
     * 7. 发送 226 响应（传输完成）
     */
    private void handleList() throws IOException {
        // 1. 检查是否已设置数据端口
        if (dataAddress == null) {
            reply(425, "Use PORT first");
            return;
        }
        
        // 2. 解析当前工作目录对应的实际路径
        Path currentDir;
        try {
            currentDir = pathValidator.resolvePath(currentWorkingDir, ".");
        } catch (Exception e) {
            reply(550, "Cannot access directory: " + e.getMessage());
            return;
        }
        
        // 3. 检查目录是否存在
        if (!Files.exists(currentDir) || !Files.isDirectory(currentDir)) {
            reply(550, "Directory does not exist");
            return;
        }
        
        // 4. 发送"即将打开数据连接"的响应
        reply(150, "Opening ASCII mode data connection for file list");
        
        // 5. 建立数据连接并传输目录列表
        DataConnection dataConn = new DataConnection();
        try {
            // 连接到客户端数据端口
            dataConn.connect(dataAddress);
            
            // 构建目录列表文本
            StringBuilder listBuilder = new StringBuilder();
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
                for (Path entry : stream) {
                    // 获取文件名
                    String filename = entry.getFileName().toString();
                    
                    // 判断是否为目录
                    if (Files.isDirectory(entry)) {
                        // 目录名后加 / 标识
                        listBuilder.append(filename).append("/\r\n");
                    } else {
                        // 普通文件，也可以显示大小
                        long size = Files.size(entry);
                        listBuilder.append(filename).append(" (").append(size).append(" bytes)\r\n");
                    }
                }
            }
            
            // 如果目录为空
            if (listBuilder.length() == 0) {
                listBuilder.append("(empty directory)\r\n");
            }
            
            // 发送列表数据
            String listText = listBuilder.toString();
            dataConn.sendText(listText);
            
            System.out.println("[ClientSession] 已发送目录列表，共 " + listText.length() + " 字节");
            
        } catch (IOException e) {
            // 数据连接失败
            reply(426, "Data connection failed: " + e.getMessage());
            return;
        } finally {
            // 无论成功与否，都要关闭数据连接
            dataConn.close();
            // 清空数据地址（要求下次使用前重新设置 PORT）
            dataAddress = null;
        }
        
        // 6. 发送传输完成响应
        reply(226, "Transfer complete");
    }
    
    /**
     * 处理 HELP 命令 - 显示支持的命令（更新）
     */
    private void handleHelp() throws IOException {
        reply(214, "Commands supported:");
        out.write("  USER <username>  - Login with username\r\n");
        out.write("  PASS <password>  - Provide password\r\n");
        out.write("  PWD              - Print working directory\r\n");
        out.write("  CWD <path>       - Change working directory\r\n");
        out.write("  PORT h1,h2,h3,h4,p1,p2 - Set data port\r\n");
        out.write("  LIST             - List directory contents\r\n");
        out.write("  QUIT             - Disconnect\r\n");
        out.write("  HELP             - Show this message\r\n");
        out.flush();
    }
    
    // ==================== 工具方法 ====================
    
    private void reply(int code, String message) throws IOException {
        String response = code + " " + message + "\r\n";
        out.write(response);
        out.flush();
        System.out.println("[ClientSession] " + currentUser + " <- " + response.trim());
    }
}
```

**关键修改点解释**：

1. **新增成员变量 `dataAddress`**：
   - 存储客户端提供的数据端口地址
   - 初始为 `null`，通过 `PORT` 命令设置

2. **handlePort()**：
   - 解析逗号分隔的参数
   - 组装 IP 地址（字符串拼接）
   - 计算端口号（`p1 * 256 + p2`）
   - 创建 `InetSocketAddress` 对象保存
   - 返回 200 响应码

3. **handleList()**：
   - **步骤 1**：检查 `dataAddress` 是否为 `null`（未设置 PORT）
   - **步骤 2**：发送 150 响应（告知客户端即将打开数据连接）
   - **步骤 3**：创建 `DataConnection` 并连接
   - **步骤 4**：使用 `Files.newDirectoryStream()` 遍历目录
   - **步骤 5**：构建目录列表文本（目录名后加 `/`，文件显示大小）
   - **步骤 6**：通过数据连接发送文本
   - **步骤 7**：关闭数据连接，清空 `dataAddress`
   - **步骤 8**：发送 226 响应（传输完成）

4. **DirectoryStream**：
   - Java NIO.2 提供的目录遍历工具
   - 自动实现 `AutoCloseable`，可用 try-with-resources
   - 比 `File.listFiles()` 更高效

---

## 编译与运行

### 重新编译

```powershell
cd "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 删除旧的 bin 目录
Remove-Item -Recurse -Force bin -ErrorAction SilentlyContinue

# 编译所有 .java 文件
mkdir bin
javac -d bin -encoding UTF-8 src\*.java
```

### 运行服务器

```powershell
cd bin
java FtpServer
```

**预期输出**：
```
[PathValidator] FTP 根目录设置为: C:\Users\...\data
[FtpServer] FTP 根目录: C:\Users\...\data
[FtpServer] 用户表已初始化
[FtpServer] 线程池已创建，容量=32
[FtpServer] FTP 服务器启动成功，监听端口 2121
[FtpServer] 等待客户端连接...
```

---

## 测试方法

### 测试前准备

由于 telnet 无法模拟数据连接，我们需要写一个简单的 Java 客户端进行测试。

**创建文件**：`test_client/SimpleFtpClient.java`

```java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * 简单 FTP 客户端（用于测试）
 */
public class SimpleFtpClient {
    private Socket controlSocket;
    private BufferedReader in;
    private BufferedWriter out;
    private ServerSocket dataServerSocket;
    
    public static void main(String[] args) {
        SimpleFtpClient client = new SimpleFtpClient();
        try {
            client.connect("127.0.0.1", 2121);
            client.login("alice", "123456");
            client.pwd();
            client.list();
            client.cwd("public");
            client.list();
            client.cwd("..");
            client.list();
            client.quit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void connect(String host, int port) throws IOException {
        controlSocket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(
            controlSocket.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(
            controlSocket.getOutputStream(), StandardCharsets.UTF_8));
        
        // 读取欢迎消息
        String welcome = in.readLine();
        System.out.println("服务器: " + welcome);
    }
    
    public void login(String user, String pass) throws IOException {
        sendCommand("USER " + user);
        sendCommand("PASS " + pass);
    }
    
    public void pwd() throws IOException {
        sendCommand("PWD");
    }
    
    public void cwd(String path) throws IOException {
        sendCommand("CWD " + path);
    }
    
    public void list() throws IOException {
        // 1. 启动本地数据端口监听
        dataServerSocket = new ServerSocket(0);  // 0 = 随机端口
        int dataPort = dataServerSocket.getLocalPort();
        
        System.out.println("客户端数据端口: " + dataPort);
        
        // 2. 发送 PORT 命令
        int p1 = dataPort / 256;
        int p2 = dataPort % 256;
        String portCmd = "PORT 127,0,0,1," + p1 + "," + p2;
        sendCommand(portCmd);
        
        // 3. 发送 LIST 命令
        out.write("LIST\r\n");
        out.flush();
        System.out.println("客户端: LIST");
        
        // 4. 读取 150 响应
        String response150 = in.readLine();
        System.out.println("服务器: " + response150);
        
        // 5. 接受服务器的数据连接
        Socket dataSocket = dataServerSocket.accept();
        System.out.println("数据连接已建立");
        
        // 6. 读取目录列表
        BufferedReader dataIn = new BufferedReader(new InputStreamReader(
            dataSocket.getInputStream(), StandardCharsets.UTF_8));
        
        System.out.println("=== 目录列表 ===");
        String line;
        while ((line = dataIn.readLine()) != null) {
            System.out.println("  " + line);
        }
        System.out.println("=================");
        
        // 7. 关闭数据连接
        dataSocket.close();
        dataServerSocket.close();
        
        // 8. 读取 226 响应
        String response226 = in.readLine();
        System.out.println("服务器: " + response226);
    }
    
    public void quit() throws IOException {
        sendCommand("QUIT");
        controlSocket.close();
    }
    
    private void sendCommand(String cmd) throws IOException {
        out.write(cmd + "\r\n");
        out.flush();
        System.out.println("客户端: " + cmd);
        
        String response = in.readLine();
        System.out.println("服务器: " + response);
    }
}
```

**编译并运行测试客户端**：

```powershell
# 创建测试客户端目录
mkdir test_client
cd test_client

# 保存上面的代码为 SimpleFtpClient.java

# 编译
javac -encoding UTF-8 SimpleFtpClient.java

# 运行（确保 FtpServer 已启动）
java SimpleFtpClient
```

**预期输出**：

```
服务器: 220 Simple FTP Server Ready
客户端: USER alice
服务器: 331 User name ok, need password
客户端: PASS 123456
服务器: 230 User alice logged in
客户端: PWD
服务器: 257 "/" is current directory
客户端数据端口: 52341
客户端: PORT 127,0,0,1,204,117
服务器: 200 PORT command successful
客户端: LIST
服务器: 150 Opening ASCII mode data connection for file list
数据连接已建立
=== 目录列表 ===
  public/
  upload/
  test.txt (20 bytes)
=================
服务器: 226 Transfer complete
客户端: CWD public
服务器: 250 Directory changed to /public
客户端数据端口: 52342
客户端: PORT 127,0,0,1,204,118
服务器: 200 PORT command successful
客户端: LIST
服务器: 150 Opening ASCII mode data connection for file list
数据连接已建立
=== 目录列表 ===
  readme.txt (17 bytes)
=================
服务器: 226 Transfer complete
客户端: CWD ..
服务器: 250 Directory changed to /
客户端数据端口: 52343
客户端: PORT 127,0,0,1,204,119
服务器: 200 PORT command successful
客户端: LIST
服务器: 150 Opening ASCII mode data connection for file list
数据连接已建立
=== 目录列表 ===
  public/
  upload/
  test.txt (20 bytes)
=================
服务器: 226 Transfer complete
客户端: QUIT
服务器: 221 Goodbye
```

---

## 常见错误与排查

| 错误 | 原因 | 解决方法 |
|------|------|---------|
| `425 Use PORT first` | 未发送 PORT 命令就执行 LIST | 先发送 `PORT` 命令设置数据端口 |
| `426 Data connection failed` | 无法连接到客户端数据端口 | 检查端口是否被占用、防火墙是否拦截 |
| `501 Invalid PORT argument` | PORT 参数格式错误 | 确保格式为 `h1,h2,h3,h4,p1,p2`，共 6 个数字 |
| `java.net.ConnectException: Connection refused` | 客户端未监听数据端口 | 确保客户端在发送 LIST 前先启动 ServerSocket 监听 |
| 目录列表为空但目录有文件 | 权限问题或路径错误 | 检查 `data/` 目录权限，确保 Java 进程可读 |
| `NumberFormatException` | PORT 参数不是数字 | 检查参数是否包含非数字字符 |

---

## Debug 技巧

### 在关键位置加日志

**在 `handlePort()` 中**：

```java
System.out.println("[DEBUG] PORT 参数: " + arg);
System.out.println("[DEBUG] IP=" + ip + ", 端口=" + port);
System.out.println("[DEBUG] dataAddress=" + dataAddress);
```

**在 `handleList()` 中**：

```java
System.out.println("[DEBUG] 当前工作目录: " + currentWorkingDir);
System.out.println("[DEBUG] 实际目录路径: " + currentDir);
System.out.println("[DEBUG] 数据地址: " + dataAddress);
```

**在 `DataConnection.connect()` 中**：

```java
System.out.println("[DEBUG] 正在连接到: " + address);
System.out.println("[DEBUG] 连接成功: " + dataSocket.isConnected());
```

---

## 检查清单

完成 Day3，你应该能做到：

- [ ] `DataConnection.java` 创建完成，编译无错误
- [ ] `ClientSession.java` 修改完成，增加了 PORT 和 LIST 命令
- [ ] 服务器启动正常
- [ ] 简单客户端编译成功
- [ ] 客户端连接服务器，登录成功
- [ ] 发送 `PORT` 命令收到 `200` 响应
- [ ] 发送 `LIST` 命令收到 `150` 响应
- [ ] 数据连接建立成功
- [ ] 收到完整的目录列表（包含 `public/`、`upload/`、`test.txt`）
- [ ] 收到 `226 Transfer complete` 响应
- [ ] 切换到子目录后再执行 `LIST`，显示该子目录内容
- [ ] 多次执行 `LIST`，每次都需要重新发送 `PORT`（数据连接不复用）

---

## 核心概念回顾

### 为什么数据连接不复用？

**安全性**：每次传输后关闭连接，避免数据泄露。

**简单性**：不需要维护数据连接状态。

**标准规定**：FTP RFC 959 规定数据连接是临时的。

### PORT 命令的端口计算

```
端口 5000 → p1=5000/256=19, p2=5000%256=136 → PORT ...,19,136
端口 20000 → p1=20000/256=78, p2=20000%256=32 → PORT ...,78,32
```

**Java 代码验证**：

```java
int port = 5000;
int p1 = port / 256;  // 19
int p2 = port % 256;  // 136
int recovered = p1 * 256 + p2;  // 5000 ✓
```

### 主动模式 vs 被动模式

| 模式 | 谁发起数据连接 | 适用场景 |
|------|---------------|---------|
| **主动模式** | 服务器连接客户端 | 客户端无防火墙、NAT |
| **被动模式** | 客户端连接服务器 | 客户端在 NAT 后（现代常用）|

**Day3 实现的是主动模式**；被动模式会在 Day6 或 Day7 讲解（可选）。

---

## 下一步预告（Day4）

- 实现 `RETR <filename>` 命令：下载文件
- 通过数据连接传输二进制文件内容
- 处理文件不存在、权限不足等错误
- 支持不同大小的文件传输（分块发送）

---

## 参考资源

- [FTP 协议 RFC 959](https://www.rfc-editor.org/rfc/rfc959)
- [Java Socket 编程指南](https://docs.oracle.com/javase/tutorial/networking/sockets/)
- [Java NIO.2 文件操作](https://docs.oracle.com/javase/tutorial/essential/io/fileio.html)
- [DirectoryStream 文档](https://docs.oracle.com/javase/8/docs/api/java/nio/file/DirectoryStream.html)

---

## 常见问题（FAQ）

**Q：为什么数据连接由服务器发起，而不是客户端？**  
A：这是 FTP 主动模式的设计。历史原因是早期客户端通常是简单终端，服务器功能更强大。现代 FTP 更常用被动模式（PASV），由客户端发起数据连接。

**Q：如果客户端在 NAT 后面，主动模式能工作吗？**  
A：不能。服务器无法直接连接到 NAT 后的客户端内网地址。需要使用被动模式（PASV）。

**Q：为什么 PORT 命令用逗号而不是点号和冒号？**  
A：这是 FTP 协议的历史设计。早期网络协议偏好统一用逗号分隔所有参数。

**Q：如果 LIST 执行时目录被删除了，会怎样？**  
A：`Files.newDirectoryStream()` 会抛出 `IOException`，被 catch 后返回 550 错误码。

**Q：能否在数据连接上同时传输多个文件？**  
A：不能。FTP 规定一个数据连接只用于一次传输。多个文件需要多次数据连接。

**Q：`DirectoryStream` 和 `File.listFiles()` 有什么区别？**  
A：
- `DirectoryStream`：NIO.2 API，惰性加载，内存效率高，支持过滤器。
- `File.listFiles()`：传统 IO API，一次性加载所有文件到数组，大目录可能占用大量内存。

**Q：为什么 `sendText()` 不用 `BufferedWriter`？**  
A：数据连接的数据可能是二进制（文件），也可能是文本（目录列表）。为了统一处理，直接用字节流。文本时手动转换为字节。

---

**恭喜你完成 Day3！你已经掌握了 FTP 数据连接的核心机制，这是 FTP 协议最重要的部分。**
