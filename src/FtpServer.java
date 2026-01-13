package data;
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
