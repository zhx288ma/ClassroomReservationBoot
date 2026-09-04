package com.xuan.boot.service.impl;

import com.xuan.boot.domain.User;
import com.xuan.boot.dto.LoginRequest;
import com.xuan.boot.dto.LoginResponse;
import com.xuan.boot.dto.RegisterRequest;
import com.xuan.boot.mapper.UserMapper;
import com.xuan.boot.service.AuthService;
import com.xuan.boot.support.RedisKeys;
import com.xuan.boot.support.JwtTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtTokenService jwtTokenService;
    private final Duration tokenTtl;

    public AuthServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           StringRedisTemplate stringRedisTemplate,
                           JwtTokenService jwtTokenService,
                           @Value("${reservation.token-ttl-minutes:30}") long tokenTtlMinutes) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtTokenService = jwtTokenService;
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
    }

    @Override
    public void register(RegisterRequest request) {
        User exists = userMapper.findByPhone(request.getPhone());
        if (exists != null) {
            throw new IllegalArgumentException("手机号已注册");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPhone(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");
        user.setStatus(1);
        userMapper.insert(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByPhone(request.getPhone());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new IllegalArgumentException("账号不存在或已禁用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("手机号或密码错误");
        }
        String token = jwtTokenService.createToken(user);
        stringRedisTemplate.opsForValue().set(RedisKeys.LOGIN_TOKEN + token, String.valueOf(user.getId()), tokenTtl);
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getRole());
    }

    @Override
    public User getUserByToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        JwtTokenService.JwtClaims claims = jwtTokenService.parse(token);
        if (claims == null) {
            return null;
        }
        String key = RedisKeys.LOGIN_TOKEN + token;
        String userId = stringRedisTemplate.opsForValue().get(key);
        if (userId == null) {
            return null;
        }
        stringRedisTemplate.expire(key, tokenTtl);
        if (!String.valueOf(claims.getUserId()).equals(userId)) {
            return null;
        }
        return userMapper.findById(claims.getUserId());
    }

    @Override
    public void logout(String token) {
        if (token != null) {
            stringRedisTemplate.delete(RedisKeys.LOGIN_TOKEN + token);
        }
    }
}
