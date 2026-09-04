package com.xuan.boot;

import com.xuan.boot.config.SecurityBeans;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityBeansTest {
    @Test
    void passwordEncoderShouldEncodeAndMatch() {
        PasswordEncoder encoder = new SecurityBeans().passwordEncoder();
        String encoded = encoder.encode("123456");
        Assertions.assertNotEquals("123456", encoded);
        Assertions.assertTrue(encoder.matches("123456", encoded));
    }
}
