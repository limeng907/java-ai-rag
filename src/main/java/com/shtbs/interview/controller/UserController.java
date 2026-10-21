package com.shtbs.interview.controller;

import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.common.Result;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.dto.UserCreateDTO;
import com.shtbs.interview.dto.UserLoginDTO;
import com.shtbs.interview.dto.UserRegisterDTO;
import com.shtbs.interview.dto.UserUpdateDTO;
import com.shtbs.interview.entity.User;
import com.shtbs.interview.service.UserService;
import com.shtbs.interview.vo.LoginVO;
import com.shtbs.interview.vo.UserVO;
import jakarta.validation.Valid;
import com.shtbs.interview.security.RequireAdmin;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;


    public UserController(UserService userService){
        this.userService=userService;
    }

    @PostMapping()
    @RequireAdmin
    public Result<Void> addUser(@Valid @RequestBody UserCreateDTO dto){
        userService.addUser(dto);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<UserVO> getUser(@PathVariable Long id){
        requireSelf(id);
        UserVO userVO=userService.getUserById(id);
        return Result.success(userVO);
    }

    @GetMapping
    @RequireAdmin
    public Result<PageResult<UserVO>> getAllUser(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size
    ) {

        return Result.success(
                userService.getAllUser(page, size)
        );
    }

    @PatchMapping("/{id}")
    public Result<Void> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserUpdateDTO dto
    ){
        requireSelf(id);
        userService.updateUser(id,dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id){
        requireSelf(id);
        userService.deleteUser(id);
        return Result.success();
    }

    @PostMapping("/register")
    public Result<Void> register(
            @Valid @RequestBody UserRegisterDTO userRegisterDTO) {

        userService.register(userRegisterDTO);

        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(
            @Valid @RequestBody UserLoginDTO loginDTO) {

        LoginVO loginVO =
                userService.login(loginDTO);

        return Result.success(loginVO);
    }

    @PostMapping("/code")
    public Result<Void> sendCode(
            @RequestParam String email) {

        userService.sendRegisterCode(email);

        return Result.success();
    }

    @PostMapping("/logout")
    public Result<Void> logout() {

        userService.logout();

        return Result.success();
    }

    //测试
    @GetMapping("/me")
    public Result<Long> getCurrentUser() {

        Long userId =
                UserContext.getUserId();

        return Result.success(userId);
    }

    private void requireSelf(Long id) {
        if (id == null || !id.equals(UserContext.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }


}
