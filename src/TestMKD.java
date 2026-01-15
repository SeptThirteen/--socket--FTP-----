package data;

public class TestMKD {
    public static void main(String[] args) throws Exception {
        SimpleFtpClient client = new SimpleFtpClient();
        
        // 连接并登录
        client.connect("127.0.0.1", 2121);
        client.login("alice", "123456");
        
        // 测试创建目录
        System.out.println("\n========== 测试 MKD 命令 ==========");
        
        // 显示当前目录
        client.pwd();
        
        // 创建目录
        client.mkd("newdir");
        
        // 再次创建相同目录（应该失败）
        client.mkd("newdir");
        
        // 列出目录内容
        client.list();
        
        // 断开连接
        client.quit();
    }
}
