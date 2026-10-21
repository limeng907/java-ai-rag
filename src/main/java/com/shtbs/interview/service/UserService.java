package com.shtbs.interview.service;

import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.dto.UserCreateDTO;
import com.shtbs.interview.dto.UserLoginDTO;
import com.shtbs.interview.dto.UserRegisterDTO;
import com.shtbs.interview.dto.UserUpdateDTO;
import com.shtbs.interview.entity.User;
import com.shtbs.interview.vo.LoginVO;
import com.shtbs.interview.vo.UserVO;

import java.util.List;


public interface UserService {
    void addUser(UserCreateDTO dto);

    UserVO getUserById(Long id);

    PageResult<UserVO> getAllUser(Integer page, Integer size);

    void updateUser(Long id, UserUpdateDTO user);

    void deleteUser(Long id);

    void register(UserRegisterDTO dto);

    LoginVO login(UserLoginDTO dto);

    void sendRegisterCode(String email);

    void logout();
}
