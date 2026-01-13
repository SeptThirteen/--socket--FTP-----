package data;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    
    /** FTP 虚拟根目录 */
    private static final String FTP_ROOT_DIR = "data";

    /** FTP 控制端口 */
    private static final int CONTROL_PORT = 2121;
    
    /** 线程池：最多同时处理 32 个连接 */
    private static final int POOL_SIZE = 32;
    
    /**
     * 主方法
     */
    public static void main(String[] args) {
        System.out.println("[FtpServer] FTP 服务器启动中...");

        // 1.创建根目录
        Path rootPath = Paths.get(FTP_ROOT_DIR);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            System.err.println("[FtpServer] 错误：根目录不存在或不是目录: " + rootPath.toAbsolutePath());
            System.err.println("[FtpServer] 请创建 data 目录并重新运行");
            return;
        }
        
        System.out.println("[FtpServer] FTP 根目录: " + rootPath.toAbsolutePath());
    

        // 2. 创建用户存储管理器
        UserStore userStore = UserStore.create();
        System.out.println("[FtpServer] 用户表已初始化");
        
        // 3. 创建线程池
        // newFixedThreadPool(POOL_SIZE)：创建固定大小的线程池
        // 它可以最多同时运行 32 个任务，超过的任务会排队等候
        ExecutorService threadPool = Executors.newFixedThreadPool(POOL_SIZE);

        
        System.out.println("[FtpServer] 线程池已创建，容量=" + POOL_SIZE);
        
        try {
            // 4. 启动服务器
            ServerSocket serverSocket = new ServerSocket(CONTROL_PORT);
            System.out.println("[FtpServer] FTP 服务器启动成功，监听端口 " + CONTROL_PORT);
            System.out.println("[FtpServer] 等待客户端连接...");
            
            // 5. 主循环：接受连接
            int clientCount = 0;
            while (true) {
                // 接受客户端连接（阻塞直到有连接到来）
                Socket clientSocket = serverSocket.accept();
                clientCount++;
                // 输出连接信息
                String clientAddr = clientSocket.getInetAddress().getHostAddress() + ":" + 
                                    clientSocket.getPort();
                System.out.println("[FtpServer] 客户端 #" + clientCount + " 已连接: " + clientAddr);
                
                try {
                    // 创建会话，传入根目录路径
                    ClientSession session = new ClientSession(
                        clientSocket, 
                        FTP_ROOT_DIR,  // 传入根目录
                        userStore
                    );
                    
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
