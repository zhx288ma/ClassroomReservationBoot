package com.xuan.boot.service;

import com.xuan.boot.domain.User;
import com.xuan.boot.dto.LoginRequest;
import com.xuan.boot.dto.LoginResponse;
import com.xuan.boot.dto.RegisterRequest;

public interface AuthService {
    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    User getUserByToken(String token);

    void logout(String token);
}
