package com.xuan.boot.controller;

import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.dto.LoginRequest;
import com.xuan.boot.dto.LoginResponse;
import com.xuan.boot.dto.RegisterRequest;
import com.xuan.boot.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证接口")
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public ApiResponse<Void> register(@Validated @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.ok("注册成功", null);
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Validated @RequestBody LoginRequest request) {
        return ApiResponse.ok("登录成功", authService.login(request));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "X-Token", required = false) String token,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(resolveToken(token, authorization));
        return ApiResponse.ok("退出成功", null);
    }

    private String resolveToken(String token, String authorization) {
        if ((token == null || token.trim().isEmpty()) && authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length());
        }
        return token;
    }
}
