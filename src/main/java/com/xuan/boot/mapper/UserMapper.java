package com.xuan.boot.mapper;

import com.xuan.boot.domain.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper {
    @Select("select * from tb_user where id = #{id}")
    User findById(Long id);

    @Select("select * from tb_user where phone = #{phone}")
    User findByPhone(String phone);

    @Select("select * from tb_user where role='ADMIN' and status=1")
    List<User> listActiveAdmins();

    @Insert("insert into tb_user(username, password, phone, role, status) " +
            "values(#{username}, #{password}, #{phone}, #{role}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);
}
