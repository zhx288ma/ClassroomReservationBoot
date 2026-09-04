package com.xuan.boot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.xuan.boot.mapper")
@EnableScheduling
@SpringBootApplication
public class ClassroomReservationBootApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClassroomReservationBootApplication.class, args);
    }
}
