package com.xuan.boot.support;

import com.xuan.boot.domain.User;

public final class UserContext {
    private static final ThreadLocal<User> USER_HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(User user) {
        USER_HOLDER.set(user);
    }

    public static User get() {
        return USER_HOLDER.get();
    }

    public static User getRequired() {
        User user = USER_HOLDER.get();
        if (user == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return user;
    }

    public static void remove() {
        USER_HOLDER.remove();
    }
}
