package data;

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
        return users.containsKey(username);//用的是map的方法containsKey，检查键是否存在
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
     * 工厂方法：创建一个新的 UserStore 实例、
     * @return 新的 UserStore 实例
     * 这样可以更灵活地创建对象
     */
    public static UserStore create() {
        return new UserStore();
    }
}